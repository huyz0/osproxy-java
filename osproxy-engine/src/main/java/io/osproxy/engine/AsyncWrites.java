package io.osproxy.engine;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.osproxy.core.ErrorCode;
import io.osproxy.rewrite.Json;
import io.osproxy.sink.DocOp;
import io.osproxy.sink.WriteBatch;
import io.osproxy.spi.RequestCtx;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Per-request async write mode (the Rust project's ADR-010): a write
 * carrying {@code x-osproxy-write-mode: async} is fully transformed, then
 * durably enqueued instead of dispatched, answering an honest
 * {@code 202 {status, op_id}}. Refuse-don't-lie: no sink configured is 503,
 * a non-single-doc-write with the header is 400 — a 202 is only ever sent
 * after the broker acknowledged.
 */
public final class AsyncWrites {

    /** The per-request mode header. */
    public static final String WRITE_MODE_HEADER = "x-osproxy-write-mode";

    /**
     * The durable enqueue seam. Implementations block until the queue has
     * acknowledged (the server adapts its AckProducer to this).
     */
    public interface AsyncWriteSink {
        /** Enqueues one transformed operation envelope, or throws. */
        void enqueue(String key, byte[] envelope) throws Exception;
    }

    private AsyncWrites() {}

    /** Whether the request asked for async mode. */
    static boolean wantsAsync(RequestCtx ctx) {
        return ctx.header(WRITE_MODE_HEADER)
                .map(v -> v.equalsIgnoreCase("async"))
                .orElse(false);
    }

    /**
     * Enqueues one fully-transformed write op. The envelope carries
     * everything a drain worker needs to commit it later.
     */
    static PipelineResponse enqueue(AsyncWriteSink sink, WriteBatch.Op op) {
        ObjectNode envelope = Json.MAPPER.createObjectNode();
        envelope.put("op", switch (op.op()) {
            case DocOp.Index ignored -> "index";
            case DocOp.Create ignored -> "create";
            case DocOp.Update ignored -> "update";
            case DocOp.Delete ignored -> "delete";
        });
        envelope.put("cluster", op.target().cluster().value());
        envelope.put("index", op.target().index().value());
        envelope.put("physical_id", op.op().physicalId());
        op.op().routing().ifPresent(r -> envelope.put("routing", r));
        envelope.put("epoch", op.epoch().generation());
        switch (op.op()) {
            case DocOp.Index(var id, byte[] doc, var r) -> putDoc(envelope, doc);
            case DocOp.Create(var id, byte[] doc, var r) -> putDoc(envelope, doc);
            case DocOp.Update(var id, byte[] doc, var r) -> putDoc(envelope, doc);
            case DocOp.Delete ignored -> {}
        }
        byte[] bytes = Json.writeBytes(envelope);
        String opId = opId(bytes);
        try {
            sink.enqueue(op.op().physicalId(), bytes);
        } catch (Exception e) {
            // The broker did not acknowledge: say so, never fake a 202.
            return PipelineResponse.error(ErrorCode.UPSTREAM_UNAVAILABLE);
        }
        return new PipelineResponse(202,
                ("{\"status\":\"accepted\",\"op_id\":\"" + opId + "\"}")
                        .getBytes(StandardCharsets.UTF_8));
    }

    private static void putDoc(ObjectNode envelope, byte[] doc) {
        try {
            envelope.set("doc", Json.MAPPER.readTree(doc));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("transformed doc must be valid json", e);
        }
    }

    /** A deterministic id: the same envelope always names the same op. */
    private static String opId(byte[] envelope) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(envelope);
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
