package io.osproxy.engine;

import static io.osproxy.engine.PipelineTestSupport.json;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.osproxy.rewrite.Json;
import io.osproxy.sink.MemorySink;
import io.osproxy.spi.Principal;
import io.osproxy.spi.RequestCtx;
import io.osproxy.spi.RequestCtx.HttpMethod;
import io.osproxy.tenancy.TenancyRouter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** ADR-010: async write mode — honest 202s, refuse-don't-lie everywhere else. */
class PipelineAsyncWriteTest {

    /** A recording sink that can be told to refuse. */
    private static final class RecordingSink implements AsyncWrites.AsyncWriteSink {
        final List<byte[]> envelopes = new ArrayList<>();
        boolean failing;

        @Override
        public void enqueue(String key, byte[] envelope) throws Exception {
            if (failing) {
                throw new Exception("broker down");
            }
            envelopes.add(envelope);
        }
    }

    private static RequestCtx asyncRequest(HttpMethod method, String path, byte[] body) {
        Classify.Classified c = Classify.classify(method, path);
        return new RequestCtx(
                method, path, c.endpoint(), c.logicalIndex(), c.docId(),
                List.of(Map.entry("x-tenant", "acme"),
                        Map.entry(AsyncWrites.WRITE_MODE_HEADER, "async")),
                body, new Principal("u", Map.of("tenant", "acme")));
    }

    private static Pipeline pipeline(MemorySink sink, AsyncWrites.AsyncWriteSink async) {
        return new Pipeline(
                new TenancyRouter(PipelineTestSupport.sharedIndexSpi(true)),
                sink, sink, Optional.empty(), Optional.ofNullable(async));
    }

    @Test
    void anAsyncWriteIsTransformedEnqueuedAndAccepted() throws Exception {
        var recorder = new RecordingSink();
        var sink = new MemorySink();
        var pipeline = pipeline(sink, recorder);

        PipelineResponse resp = pipeline.handle(asyncRequest(
                HttpMethod.PUT, "/orders/_doc/7", "{\"m\":\"x\"}".getBytes()));
        assertThat(resp.status()).isEqualTo(202);
        JsonNode ack = json(resp);
        assertThat(ack.get("status").textValue()).isEqualTo("accepted");
        assertThat(ack.get("op_id").textValue()).hasSize(32);

        // The envelope carries the fully transformed op: prefixed id,
        // injected marker, routing, epoch — a drain worker needs nothing else.
        JsonNode envelope = Json.MAPPER.readTree(recorder.envelopes.get(0));
        assertThat(envelope.get("op").textValue()).isEqualTo("index");
        assertThat(envelope.get("physical_id").textValue()).isEqualTo("acme:7");
        assertThat(envelope.get("routing").textValue()).isEqualTo("acme");
        assertThat(envelope.at("/doc/_tenant").textValue()).isEqualTo("acme");
        assertThat(envelope.get("epoch").longValue()).isZero();

        // Nothing was written synchronously.
        var direct = pipeline.handle(PipelineTestSupport.request(
                HttpMethod.GET, "/orders/_doc/7", "acme"));
        assertThat(direct.status()).isEqualTo(404);

        // Deletes enqueue too, without a doc.
        PipelineResponse del = pipeline.handle(asyncRequest(
                HttpMethod.DELETE, "/orders/_doc/7", new byte[0]));
        assertThat(del.status()).isEqualTo(202);
        assertThat(Json.MAPPER.readTree(recorder.envelopes.get(1)).has("doc")).isFalse();
    }

    @Test
    void refuseDontLie() {
        var sink = new MemorySink();
        var recorder = new RecordingSink();

        // No sink wired: 503, never a fake 202.
        var noSink = pipeline(sink, null);
        assertThat(noSink.handle(asyncRequest(
                        HttpMethod.PUT, "/orders/_doc/1", "{}".getBytes()))
                .status()).isEqualTo(503);

        // Broker refuses the ack: 503.
        recorder.failing = true;
        var failing = pipeline(sink, recorder);
        assertThat(failing.handle(asyncRequest(
                        HttpMethod.PUT, "/orders/_doc/1", "{}".getBytes()))
                .status()).isEqualTo(503);

        // Async on anything but a single-doc write: 400.
        recorder.failing = false;
        var ok = pipeline(sink, recorder);
        assertThat(ok.handle(asyncRequest(
                        HttpMethod.POST, "/_bulk", "{\"index\":{}}\n{}\n".getBytes()))
                .status()).isEqualTo(400);
        assertThat(ok.handle(asyncRequest(HttpMethod.GET, "/orders/_doc/1", new byte[0]))
                .status()).isEqualTo(400);
        assertThat(ok.handle(asyncRequest(
                        HttpMethod.POST, "/orders/_search", new byte[0]))
                .status()).isEqualTo(400);

        // Same op, same id: the op_id is deterministic.
        var a = json(ok.handle(asyncRequest(
                HttpMethod.PUT, "/orders/_doc/9", "{\"m\":1}".getBytes())));
        var b = json(ok.handle(asyncRequest(
                HttpMethod.PUT, "/orders/_doc/9", "{\"m\":1}".getBytes())));
        assertThat(a.get("op_id")).isEqualTo(b.get("op_id"));
    }

    @Test
    void syncWritesAreUntouchedByTheAsyncWiring() {
        var sink = new MemorySink();
        var pipeline = pipeline(sink, new RecordingSink());
        assertThat(pipeline.handle(PipelineTestSupport.request(
                        HttpMethod.PUT, "/orders/_doc/1", "acme", "{}".getBytes()))
                .status()).isEqualTo(201);
        assertThat(pipeline.handle(PipelineTestSupport.request(
                        HttpMethod.GET, "/orders/_doc/1", "acme"))
                .status()).isEqualTo(200);
    }
}
