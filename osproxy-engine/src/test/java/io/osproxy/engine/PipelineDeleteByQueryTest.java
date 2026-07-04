package io.osproxy.engine;

import static io.osproxy.engine.PipelineTestSupport.json;
import static org.assertj.core.api.Assertions.assertThat;

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

/** The `_delete_by_query` async expansion: refuse-don't-lie, then a capped match+delete. */
class PipelineDeleteByQueryTest {

    private static final class RecordingSink implements AsyncWrites.AsyncWriteSink {
        final List<byte[]> envelopes = new ArrayList<>();

        @Override
        public void enqueue(String key, byte[] envelope) {
            envelopes.add(envelope);
        }
    }

    private static RequestCtx dbqRequest(String tenant, boolean async) {
        HttpMethod method = HttpMethod.POST;
        String path = "/orders/_delete_by_query";
        Classify.Classified c = Classify.classify(method, path);
        List<Map.Entry<String, String>> headers = new ArrayList<>(
                List.of(Map.entry("x-tenant", tenant)));
        if (async) {
            headers.add(Map.entry(AsyncWrites.WRITE_MODE_HEADER, "async"));
        }
        return new RequestCtx(
                method, path, c.endpoint(), c.logicalIndex(), c.docId(),
                headers, "{\"query\":{\"match_all\":{}}}".getBytes(),
                new Principal("u-" + tenant, Map.of("tenant", tenant)));
    }

    private static Pipeline pipeline(MemorySink sink, AsyncWrites.AsyncWriteSink async,
            boolean expansionEnabled) {
        Pipeline p = new Pipeline(
                new TenancyRouter(PipelineTestSupport.sharedIndexSpi(true)),
                sink, sink, Optional.empty(), Optional.ofNullable(async));
        return p.withDeleteByQueryExpansion(expansionEnabled);
    }

    @Test
    void refusedWithoutAsyncMode() {
        var sink = new MemorySink();
        var pipeline = pipeline(sink, new RecordingSink(), true);
        var resp = pipeline.handle(dbqRequest("acme", false));
        assertThat(resp.status()).isEqualTo(400);
        assertThat(json(resp).get("error").textValue())
                .isEqualTo("delete_by_query is only supported in async write mode");
    }

    @Test
    void refusedWhenExpansionDisabled() {
        var sink = new MemorySink();
        var pipeline = pipeline(sink, new RecordingSink(), false);
        var resp = pipeline.handle(dbqRequest("acme", true));
        assertThat(resp.status()).isEqualTo(400);
        assertThat(json(resp).get("error").textValue())
                .isEqualTo("delete_by_query expansion is not enabled on this proxy");
    }

    @Test
    void unavailableWithNoAsyncSinkConfigured() {
        var sink = new MemorySink();
        var pipeline = pipeline(sink, null, true);
        var resp = pipeline.handle(dbqRequest("acme", true));
        assertThat(resp.status()).isEqualTo(422);
        assertThat(json(resp).get("error").textValue())
                .isEqualTo("async write mode is not available on this proxy");
    }

    @Test
    void matchesOnlyThePartitionAndEnqueuesADeletePerHit() throws Exception {
        var sink = new MemorySink();
        var recorder = new RecordingSink();
        var pipeline = pipeline(sink, recorder, true);

        // Two acme docs, one globex doc — the partition filter must exclude it.
        pipeline.handle(PipelineTestSupport.request(
                HttpMethod.PUT, "/orders/_doc/1", "acme", "{}".getBytes()));
        pipeline.handle(PipelineTestSupport.request(
                HttpMethod.PUT, "/orders/_doc/2", "acme", "{}".getBytes()));
        pipeline.handle(PipelineTestSupport.request(
                HttpMethod.PUT, "/orders/_doc/1", "globex", "{}".getBytes()));

        var resp = pipeline.handle(dbqRequest("acme", true));
        assertThat(resp.status()).isEqualTo(200);
        var body = json(resp);
        assertThat(body.get("total").asLong()).isEqualTo(2);
        assertThat(body.get("deleted").asLong()).isEqualTo(2);
        assertThat(body.get("failures")).isEmpty();
        assertThat(recorder.envelopes).hasSize(2);

        // globex's document is untouched — a search after the fact still finds it.
        var globexGet = pipeline.handle(PipelineTestSupport.request(
                HttpMethod.GET, "/orders/_doc/1", "globex"));
        assertThat(globexGet.status()).isEqualTo(200);
    }

    @Test
    void overCapMatchSetIsRefused() throws Exception {
        var sink = new MemorySink();
        var pipeline = pipeline(sink, new RecordingSink(), true);
        for (int i = 0; i < 10_001; i++) {
            var ack = sink.write(List.of(new io.osproxy.sink.WriteBatch.Op(
                    new io.osproxy.core.Target(
                            new io.osproxy.core.ClusterId("c1"), new io.osproxy.core.IndexName("shared")),
                    new io.osproxy.sink.DocOp.Index(
                            "acme:" + i, "{\"_tenant\":\"acme\"}".getBytes(), Optional.of("acme")),
                    io.osproxy.core.Epoch.INITIAL)));
            assertThat(ack.results().get(0).ok()).isTrue();
        }

        var resp = pipeline.handle(dbqRequest("acme", true));
        assertThat(resp.status()).isEqualTo(400);
        assertThat(json(resp).get("error").textValue())
                .isEqualTo("delete_by_query match set exceeds the proxy cap");
    }
}
