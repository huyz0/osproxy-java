package io.osproxy.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.osproxy.core.ErrorCode;
import io.osproxy.rewrite.Bulk;
import io.osproxy.rewrite.Fields;
import io.osproxy.rewrite.Json;
import io.osproxy.rewrite.Multi;
import io.osproxy.rewrite.Queries;
import io.osproxy.rewrite.RewriteException;
import io.osproxy.sink.DocOp;
import io.osproxy.sink.Reader;
import io.osproxy.sink.SinkException;
import io.osproxy.sink.WriteBatch;
import io.osproxy.spi.DocIdRule;
import io.osproxy.spi.RequestCtx;
import io.osproxy.spi.RouteDecision;
import io.osproxy.spi.SpiException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The multi-item endpoints: {@code _bulk}, {@code _mget}, {@code _msearch}.
 * Each item is resolved and transformed independently (documents in one
 * payload may belong to different partitions), dispatched, and the responses
 * re-interleaved in request order — the demux/remux the Rust engine performs
 * (its docs/04 §5).
 */
final class MultiOps {

    private final Pipeline pipeline;

    MultiOps(Pipeline pipeline) {
        this.pipeline = pipeline;
    }

    PipelineResponse bulk(RequestCtx ctx)
            throws SpiException, RewriteException, SinkException {
        List<Bulk.Item> items = Bulk.parseBulk(ctx.body());
        if (items.isEmpty()) {
            return PipelineResponse.error(ErrorCode.MALFORMED_REQUEST);
        }
        // A per-item failure (stale epoch today) must not abort the whole
        // batch — real OpenSearch, and this endpoint's own streaming twin,
        // both report bulk failures per item. prepare() never throws for
        // that condition; it returns an already-failed ItemCtx instead, so
        // only the items that actually need dispatching go into ops.
        List<ItemCtx> contexts = new ArrayList<>(items.size());
        for (Bulk.Item item : items) {
            contexts.add(prepare(ctx, item));
        }

        if (AsyncWrites.wantsAsync(ctx)) {
            return bulkAsync(contexts);
        }

        List<WriteBatch.Op> ops = new ArrayList<>(items.size());
        for (ItemCtx ic : contexts) {
            ic.op().ifPresent(ops::add);
        }
        WriteBatch.Ack ack = pipeline.sink().write(ops);

        ObjectNode out = Json.MAPPER.createObjectNode();
        out.put("took", 0);
        boolean errors = false;
        ArrayNode itemsOut = out.putArray("items");
        int dispatched = 0;
        for (ItemCtx ic : contexts) {
            WriteBatch.OpResult result = ic.op().isPresent()
                    ? ack.results().get(dispatched++)
                    : ic.preparedFailure().orElseThrow();
            errors |= !result.ok();
            ObjectNode verb = itemsOut.addObject().putObject(ic.verbKey());
            verb.put("_index", ic.logicalIndex());
            verb.put("_id", ic.logicalId());
            verb.put("status", result.status());
            verb.put("result", result.result());
        }
        out.put("errors", errors);
        return new PipelineResponse(200, Json.writeBytes(out));
    }

    /**
     * Bulk's async twin: each item is enqueued individually (the broker's
     * own producer batching, not this loop, is what amortizes the network
     * cost, see {@code AsyncWrites.enqueue}), and the response mirrors real
     * bulk's {@code items[]} shape, each entry replacing the usual
     * {@code result}/version fields with {@code op_id} (there is no write
     * result yet, only a durable acknowledgement of the enqueue). A
     * per-item enqueue failure is that item's own {@code status}, same
     * partial-success contract as the synchronous path; it never aborts
     * the rest of the batch.
     */
    private PipelineResponse bulkAsync(List<ItemCtx> contexts) {
        AsyncWrites.AsyncWriteSink sink = pipeline.asyncSink().orElseThrow();
        ObjectNode out = Json.MAPPER.createObjectNode();
        out.put("took", 0);
        boolean errors = false;
        ArrayNode itemsOut = out.putArray("items");
        for (ItemCtx ic : contexts) {
            ObjectNode verb = itemsOut.addObject().putObject(ic.verbKey());
            verb.put("_index", ic.logicalIndex());
            verb.put("_id", ic.logicalId());
            if (ic.op().isEmpty()) {
                WriteBatch.OpResult failure = ic.preparedFailure().orElseThrow();
                errors = true;
                verb.put("status", failure.status());
                verb.put("result", failure.result());
                continue;
            }
            PipelineResponse enqueued = AsyncWrites.enqueue(sink, ic.op().get());
            verb.put("status", enqueued.status());
            if (enqueued.status() == 202) {
                verb.put("result", "accepted");
                verb.put("op_id", opId(enqueued.body()));
            } else {
                errors = true;
                verb.put("result", "error");
            }
        }
        out.put("errors", errors);
        return new PipelineResponse(200, Json.writeBytes(out));
    }

    /** Pulls {@code op_id} back out of an accepted {@code AsyncWrites.enqueue} body. */
    private static String opId(byte[] acceptedBody) {
        try {
            return Json.MAPPER.readTree(acceptedBody).path("op_id").asText();
        } catch (java.io.IOException e) {
            // enqueue() only ever hands back its own well-formed body on 202.
            throw new IllegalStateException("accepted enqueue body must be valid json", e);
        }
    }

    /**
     * Validates the stream is non-empty and its first item parses, without
     * dispatching anything — so the ingress can still send a proper error
     * status for an empty or malformed-at-the-first-line body before
     * committing the response as 200. Unwraps the unchecked carrier {@link
     * Bulk#parseBulkStream} uses back into the checked {@link
     * RewriteException} it wraps.
     */
    static java.util.Iterator<Bulk.Item> peekBulkStream(com.fasterxml.jackson.core.JsonParser parser)
            throws RewriteException {
        java.util.Iterator<Bulk.Item> items = Bulk.parseBulkStream(parser);
        try {
            if (!items.hasNext()) {
                throw new RewriteException(
                        RewriteException.Kind.MALFORMED_MULTI, "empty bulk body");
            }
        } catch (RuntimeException e) {
            if (e.getCause() instanceof RewriteException re) {
                throw re;
            }
            throw e;
        }
        return items;
    }

    /**
     * Streaming twin of {@link #bulk}: dispatches one item at a time from an
     * already-validated iterator and writes each result to {@code gen} as it
     * completes, so a bulk body is never buffered as a byte[] regardless of
     * its size. Trade-off: once the first item has been written, a failure
     * on a later item cannot unwind the response's 200 status — the caller
     * has already committed it. That mirrors OpenSearch's own bulk
     * semantics (partial success, reported per item) more than it departs
     * from them.
     */
    void bulkStreaming(
            RequestCtx ctx, java.util.Iterator<Bulk.Item> items,
            com.fasterxml.jackson.core.JsonGenerator gen)
            throws java.io.IOException, SpiException, RewriteException, SinkException {
        gen.writeStartObject();
        gen.writeNumberField("took", 0);
        gen.writeArrayFieldStart("items");
        boolean errors = false;
        try {
            while (items.hasNext()) {
                Bulk.Item item = items.next();
                ItemCtx ic = prepare(ctx, item);
                WriteBatch.OpResult result = ic.op().isPresent()
                        ? pipeline.sink().write(List.of(ic.op().get())).results().get(0)
                        : ic.preparedFailure().orElseThrow();
                errors |= !result.ok();
                gen.writeStartObject();
                gen.writeObjectFieldStart(ic.verbKey());
                gen.writeStringField("_index", ic.logicalIndex());
                gen.writeStringField("_id", ic.logicalId());
                gen.writeNumberField("status", result.status());
                gen.writeStringField("result", result.result());
                gen.writeEndObject();
                gen.writeEndObject();
            }
        } catch (RuntimeException e) {
            if (e.getCause() instanceof RewriteException re) {
                throw re;
            }
            throw e;
        }
        gen.writeEndArray();
        gen.writeBooleanField("errors", errors);
        gen.writeEndObject();
        gen.flush();
    }

    /**
     * Per-item routing/transform state carried through to response assembly.
     * {@code op} is empty exactly when {@code preparedFailure} is present —
     * a per-item condition (stale epoch today) that must be reported as
     * this item's own result, not thrown, so it can't abort the rest of
     * the batch or (on the streaming path) the items already written.
     */
    private record ItemCtx(
            Optional<WriteBatch.Op> op, String verbKey, String logicalIndex, String logicalId,
            Optional<WriteBatch.OpResult> preparedFailure) {}

    private ItemCtx prepare(RequestCtx ctx, Bulk.Item item)
            throws SpiException, RewriteException {
        String logicalIndex = item.index()
                .or(ctx::logicalIndex)
                .orElseThrow(() -> new SpiException.UnsupportedEndpoint(ctx.endpoint()));
        // Each item is routed as if it were a single-doc request on its index.
        RequestCtx itemCtx = withIndex(ctx, logicalIndex, item.id());
        ObjectNode doc = item.doc().orElse(null);
        RouteDecision decision = pipeline.router().route(itemCtx, doc);
        String logicalId = item.id().orElseGet(() -> UUID.randomUUID().toString());
        if (!pipeline.router().spi().admitWrite(decision.partition(), decision.epoch())) {
            return new ItemCtx(
                    Optional.empty(), item.action().key(), logicalIndex, logicalId,
                    Optional.of(new WriteBatch.OpResult(
                            ErrorCode.STALE_EPOCH.httpStatus(), "error", logicalId)));
        }

        Optional<DocIdRule> rule = Transforms.idRule(decision.transform());
        Optional<String> routing = pipeline.routing(rule, decision);

        DocOp docOp;
        switch (item.action()) {
            case INDEX, CREATE -> {
                Map<String, JsonNode> inject = Transforms.resolveInjected(
                        Transforms.injectedFields(decision.transform()),
                        decision.partition(), itemCtx);
                Fields.injectFields(doc, inject);
                String physicalId = pipeline.physicalId(rule, decision, logicalId, doc);
                byte[] body = Json.writeBytes(doc);
                docOp = item.action() == Bulk.Action.CREATE
                        ? new DocOp.Create(physicalId, body, routing)
                        : new DocOp.Index(physicalId, body, routing);
            }
            case UPDATE -> {
                String physicalId = pipeline.mapId(rule, decision, logicalId);
                docOp = new DocOp.Update(physicalId, Json.writeBytes(doc), routing);
            }
            case DELETE -> {
                String physicalId = pipeline.mapId(rule, decision, logicalId);
                docOp = new DocOp.Delete(physicalId, routing);
            }
            default -> throw new IllegalStateException("unreachable");
        }
        return new ItemCtx(
                Optional.of(new WriteBatch.Op(decision.target(), docOp, decision.epoch())),
                item.action().key(), logicalIndex, logicalId, Optional.empty());
    }

    PipelineResponse mget(RequestCtx ctx)
            throws SpiException, RewriteException, SinkException {
        List<Multi.MgetItem> items = Multi.parseMget(ctx.body());
        ObjectNode out = Json.MAPPER.createObjectNode();
        ArrayNode docs = out.putArray("docs");
        for (Multi.MgetItem item : items) {
            String logicalIndex = item.index()
                    .or(ctx::logicalIndex)
                    .orElseThrow(() -> new SpiException.UnsupportedEndpoint(ctx.endpoint()));
            RequestCtx itemCtx = withIndex(ctx, logicalIndex, Optional.of(item.id()));
            RouteDecision decision = pipeline.router().route(itemCtx, null);
            Optional<DocIdRule> rule = Transforms.idRule(decision.transform());
            String physicalId = pipeline.mapId(rule, decision, item.id());

            Reader.Response got = pipeline.reader().get(
                    decision.target(), physicalId, pipeline.routing(rule, decision));
            ObjectNode shaped = Json.parseObject(got.body());
            Shaping.shapeGetDoc(shaped, pipeline.view(itemCtx, decision));
            // Whatever the physical layer said, the client sees its own labels.
            shaped.put("_index", logicalIndex);
            shaped.put("_id", item.id());
            docs.add(shaped);
        }
        return new PipelineResponse(200, Json.writeBytes(out));
    }

    PipelineResponse msearch(RequestCtx ctx)
            throws SpiException, RewriteException, SinkException {
        List<Multi.MsearchItem> items = Multi.parseMsearch(ctx.body());
        ObjectNode out = Json.MAPPER.createObjectNode();
        ArrayNode responses = out.putArray("responses");
        for (Multi.MsearchItem item : items) {
            String logicalIndex = item.index()
                    .or(ctx::logicalIndex)
                    .orElseThrow(() -> new SpiException.UnsupportedEndpoint(ctx.endpoint()));
            RequestCtx itemCtx = withIndex(ctx, logicalIndex, Optional.empty());
            RouteDecision decision = pipeline.router().route(itemCtx, item.body());
            Map<String, JsonNode> filter = Transforms.resolveInjected(
                    Transforms.injectedFields(decision.transform()), decision.partition(), itemCtx);
            byte[] wrapped = Queries.wrapQuery(Json.writeBytes(item.body()), filter);

            Reader.Response result = pipeline.reader().search(decision.target(), wrapped);
            ObjectNode shaped = Json.parseObject(result.body());
            Shaping.shapeSearchHits(shaped, pipeline.view(itemCtx, decision));
            shaped.put("status", result.status());
            responses.add(shaped);
        }
        return new PipelineResponse(200, Json.writeBytes(out));
    }

    /** A per-item view of the request re-labeled with the item's index/id. */
    private static RequestCtx withIndex(
            RequestCtx ctx, String logicalIndex, Optional<String> docId) {
        return new RequestCtx(
                ctx.method(), ctx.path(), ctx.endpoint(),
                Optional.of(logicalIndex), docId,
                ctx.headers(), ctx.body(), ctx.principal());
    }
}
