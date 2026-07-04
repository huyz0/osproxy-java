package io.osproxy.engine;

import static io.osproxy.engine.PipelineTestSupport.json;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.osproxy.sink.MemorySink;
import io.osproxy.spi.Principal;
import io.osproxy.spi.RequestCtx;
import io.osproxy.spi.RequestCtx.HttpMethod;
import io.osproxy.tenancy.TenancyRouter;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Scroll/PIT through the pipeline with a transparent test codec. */
class PipelineCursorTest {

    /** A codec whose seams are visible to assertions (not tamper-proof). */
    private static final CursorCodec PLAIN = new CursorCodec() {
        @Override
        public String encode(String cluster, String upstreamId) {
            return "sealed:" + cluster + ":" + Base64.getEncoder().encodeToString(
                    upstreamId.getBytes());
        }

        @Override
        public Optional<Decoded> decode(String wireId) {
            if (!wireId.startsWith("sealed:")) {
                return Optional.empty();
            }
            String[] parts = wireId.split(":", 3);
            return Optional.of(new Decoded(
                    parts[1], new String(Base64.getDecoder().decode(parts[2]))));
        }
    };

    private MemorySink sink;
    private Pipeline pipeline;

    private static RequestCtx request(
            HttpMethod method, String path, String query, byte[] body) {
        Classify.Classified c = Classify.classify(method, path);
        return new RequestCtx(
                method, path, c.endpoint(), c.logicalIndex(), c.docId(),
                List.of(Map.entry("x-tenant", "acme")), body,
                new Principal("u", Map.of("tenant", "acme")), query);
    }

    @BeforeEach
    void seed() {
        sink = new MemorySink();
        pipeline = new Pipeline(
                new TenancyRouter(PipelineTestSupport.sharedIndexSpi(true)),
                sink, sink, Optional.of(PLAIN));
        pipeline.handle(request(
                HttpMethod.PUT, "/orders/_doc/1", "", "{\"m\":\"x\"}".getBytes()));
    }

    @Test
    void scrollOpenSealsTheCursorAndShapesHits() {
        PipelineResponse resp = pipeline.handle(request(
                HttpMethod.POST, "/orders/_search", "scroll=1m",
                "{\"query\":{\"match_all\":{}}}".getBytes()));
        assertThat(resp.status()).isEqualTo(200);
        JsonNode doc = json(resp);
        // The wire id is sealed with the cluster, not the raw upstream id.
        assertThat(doc.get("_scroll_id").textValue()).startsWith("sealed:c1:");
        assertThat(doc.at("/hits/hits/0/_id").textValue()).isEqualTo("1");
        assertThat(doc.at("/hits/hits/0/_source/_tenant").isMissingNode()).isTrue();
    }

    @Test
    void scrollContinueUnsealsRoutesAndReseals() {
        String sealed = json(pipeline.handle(request(
                HttpMethod.POST, "/orders/_search", "scroll=1m", "{}".getBytes())))
                .get("_scroll_id").textValue();

        PipelineResponse next = pipeline.handle(request(
                HttpMethod.POST, "/_search/scroll", "",
                ("{\"scroll\":\"1m\",\"scroll_id\":\"" + sealed + "\"}").getBytes()));
        assertThat(next.status()).isEqualTo(200);
        JsonNode doc = json(next);
        assertThat(doc.get("_scroll_id").textValue()).startsWith("sealed:c1:");
        assertThat(doc.at("/hits/hits")).isEmpty(); // memory scroll: one batch

        PipelineResponse close = pipeline.handle(request(
                HttpMethod.DELETE, "/_search/scroll", "",
                ("{\"scroll_id\":\"" + sealed + "\"}").getBytes()));
        assertThat(close.status()).isEqualTo(200);
    }

    @Test
    void forgedCursorsAreRefusedNotForwarded() {
        PipelineResponse resp = pipeline.handle(request(
                HttpMethod.POST, "/_search/scroll", "",
                "{\"scroll_id\":\"not-sealed-at-all\"}".getBytes()));
        assertThat(resp.status()).isEqualTo(400);
    }

    @Test
    void pitOpenSearchCloseLifecycle() {
        PipelineResponse open = pipeline.handle(request(
                HttpMethod.POST, "/orders/_search/point_in_time", "keep_alive=1m",
                new byte[0]));
        assertThat(open.status()).isEqualTo(200);
        String pitId = json(open).get("pit_id").textValue();
        assertThat(pitId).startsWith("sealed:c1:");

        PipelineResponse search = pipeline.handle(request(
                HttpMethod.POST, "/_search", "",
                ("{\"pit\":{\"id\":\"" + pitId + "\"},\"query\":{\"match_all\":{}}}")
                        .getBytes()));
        assertThat(search.status()).isEqualTo(200);
        JsonNode doc = json(search);
        assertThat(doc.at("/hits/hits/0/_source/_tenant").isMissingNode()).isTrue();
        assertThat(doc.get("pit_id").textValue()).startsWith("sealed:c1:");

        PipelineResponse close = pipeline.handle(request(
                HttpMethod.DELETE, "/_search/point_in_time", "",
                ("{\"pit_id\":[\"" + pitId + "\"]}").getBytes()));
        assertThat(close.status()).isEqualTo(200);
    }

    @Test
    void withoutACodecEveryCursorRequestIsRefused() {
        var noCursor = new Pipeline(
                new TenancyRouter(PipelineTestSupport.sharedIndexSpi(true)), sink, sink);
        assertThat(noCursor.handle(request(
                        HttpMethod.POST, "/orders/_search", "scroll=1m", "{}".getBytes()))
                .status()).isEqualTo(400);
        assertThat(noCursor.handle(request(
                        HttpMethod.POST, "/_search/scroll", "",
                        "{\"scroll_id\":\"x\"}".getBytes()))
                .status()).isEqualTo(400);
    }
}
