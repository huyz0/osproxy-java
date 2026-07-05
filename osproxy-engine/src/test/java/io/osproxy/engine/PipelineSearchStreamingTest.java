package io.osproxy.engine;

import static io.osproxy.engine.PipelineTestSupport.json;
import static io.osproxy.engine.PipelineTestSupport.pipeline;
import static io.osproxy.engine.PipelineTestSupport.request;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.osproxy.core.ClusterId;
import io.osproxy.core.Epoch;
import io.osproxy.core.IndexName;
import io.osproxy.core.PartitionId;
import io.osproxy.core.Target;
import io.osproxy.sink.MemorySink;
import io.osproxy.sink.Reader;
import io.osproxy.sink.SinkException;
import io.osproxy.spi.DocIdRule;
import io.osproxy.spi.PartitionKeySpec;
import io.osproxy.spi.Placement;
import io.osproxy.spi.PlacementAt;
import io.osproxy.spi.RequestCtx.HttpMethod;
import io.osproxy.spi.TenancySpi;
import io.osproxy.tenancy.TenancyRouter;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link Pipeline#searchStreaming} driven directly, mirroring the ordinary
 * {@code searchOrCount} cases {@code PipelineSearchAndMultiTest} covers.
 */
class PipelineSearchStreamingTest {

    private MemorySink sink;
    private Pipeline pipeline;

    @BeforeEach
    void seed() {
        sink = new MemorySink();
        pipeline = pipeline(sink);
        pipeline.handle(request(
                HttpMethod.PUT, "/orders/_doc/1", "acme", "{\"msg\":\"hi\"}".getBytes()));
        pipeline.handle(request(
                HttpMethod.PUT, "/orders/_doc/1", "globex", "{\"msg\":\"other\"}".getBytes()));
    }

    @Test
    void streamsASearchIsolatedByTenantWithLogicalLabels() throws Exception {
        var ctx = request(HttpMethod.POST, "/orders/_search", "acme", new byte[0]);
        PipelineResponse resp = pipeline.searchStreaming(
                ctx, new ByteArrayInputStream(
                        "{\"query\":{\"match_all\":{}}}".getBytes(StandardCharsets.UTF_8)),
                true);
        assertThat(resp.status()).isEqualTo(200);
        JsonNode hits = json(resp).at("/hits/hits");
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).get("_id").textValue()).isEqualTo("1");
        assertThat(hits.get(0).at("/_source/_tenant").isMissingNode()).isTrue();
    }

    @Test
    void streamsACountIsolatedByTenant() throws Exception {
        var ctx = request(HttpMethod.POST, "/orders/_count", "acme", new byte[0]);
        PipelineResponse resp = pipeline.searchStreaming(
                ctx, new ByteArrayInputStream(new byte[0]), false);
        assertThat(resp.status()).isEqualTo(200);
        assertThat(json(resp).get("count").intValue()).isEqualTo(1);
    }

    @Test
    void aStreamedUnfilterableConstructIsRefused() {
        var ctx = request(HttpMethod.POST, "/orders/_search", "acme", new byte[0]);
        org.junit.jupiter.api.Assertions.assertThrows(
                io.osproxy.rewrite.RewriteException.class,
                () -> pipeline.searchStreaming(ctx, new ByteArrayInputStream(
                        "{\"suggest\":{\"s\":{\"text\":\"q\"}}}".getBytes(StandardCharsets.UTF_8)),
                        true));
    }

    @Test
    void aMalformedStreamedSearchBodyIsRefused() {
        var ctx = request(HttpMethod.POST, "/orders/_search", "acme", new byte[0]);
        org.junit.jupiter.api.Assertions.assertThrows(
                io.osproxy.rewrite.RewriteException.class,
                () -> pipeline.searchStreaming(ctx, new ByteArrayInputStream(
                        "not json".getBytes(StandardCharsets.UTF_8)), true));
    }

    @Test
    void aStreamedOverCapBodyIsRefusedWith413() {
        var ctx = request(HttpMethod.POST, "/orders/_search", "acme", new byte[0]);
        InputStream overCap = new InputStream() {
            @Override
            public int read() throws java.io.IOException {
                throw new io.osproxy.core.BodyTooLargeException("over cap");
            }
        };
        PipelineResponse resp = org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> pipeline.searchStreaming(ctx, overCap, true));
        assertThat(resp.status()).isEqualTo(413);
    }

    @Test
    void aSinkFailureDuringStreamingDispatchPropagates() {
        var ctx = request(HttpMethod.POST, "/orders/_search", "acme", new byte[0]);
        var failingReader = new FailingReader(new SinkException(
                io.osproxy.core.ErrorCode.UPSTREAM_FAILED, "boom"));
        var streamingPipeline = new Pipeline(
                new TenancyRouter(PipelineTestSupport.sharedIndexSpi(true)), sink, failingReader);
        org.junit.jupiter.api.Assertions.assertThrows(SinkException.class, () ->
                streamingPipeline.searchStreaming(ctx, new ByteArrayInputStream(
                        "{\"query\":{\"match_all\":{}}}".getBytes(StandardCharsets.UTF_8)), true));
    }

    // ---- dedicated (unfiltered) placement: the verbatim streaming path ----

    private static TenancySpi dedicatedIndexSpi() {
        return new TenancySpi() {
            @Override
            public PartitionKeySpec partitionKeySpec() {
                return new PartitionKeySpec.Header("x-tenant");
            }

            @Override
            public PlacementAt placementFor(PartitionId partition) {
                return new PlacementAt(
                        new Placement.DedicatedIndex(new ClusterId("c1"), new IndexName("orders")),
                        Epoch.INITIAL);
            }

            @Override
            public Optional<DocIdRule> docIdRule() {
                return Optional.empty();
            }
        };
    }

    @Test
    void aDedicatedPlacementStreamsVerbatimWithNoWrapping() throws Exception {
        var dedicatedSink = new MemorySink();
        var pipeline = new Pipeline(new TenancyRouter(dedicatedIndexSpi()), dedicatedSink, dedicatedSink);
        dedicatedSink.write(java.util.List.of(new io.osproxy.sink.WriteBatch.Op(
                new Target(new ClusterId("c1"), new IndexName("orders")),
                new io.osproxy.sink.DocOp.Index("1", "{\"m\":\"x\"}".getBytes(), Optional.empty()),
                Epoch.INITIAL)));
        var ctx = request(HttpMethod.POST, "/orders/_search", "acme", new byte[0]);

        PipelineResponse resp = pipeline.searchStreaming(ctx, new ByteArrayInputStream(
                "{\"query\":{\"match_all\":{}}}".getBytes(StandardCharsets.UTF_8)), true);
        assertThat(resp.status()).isEqualTo(200);
        assertThat(json(resp).at("/hits/hits")).hasSize(1);
    }

    @Test
    void aDedicatedPlacementSinkFailurePropagatesVerbatim() {
        var failingReader = new FailingReader(new SinkException(
                io.osproxy.core.ErrorCode.UPSTREAM_FAILED, "boom"));
        var pipeline = new Pipeline(new TenancyRouter(dedicatedIndexSpi()), sink, failingReader);
        var ctx = request(HttpMethod.POST, "/orders/_search", "acme", new byte[0]);
        org.junit.jupiter.api.Assertions.assertThrows(SinkException.class, () ->
                pipeline.searchStreaming(ctx, new ByteArrayInputStream(
                        "{\"query\":{\"match_all\":{}}}".getBytes(StandardCharsets.UTF_8)), true));
    }

    @Test
    void aDedicatedPlacementOverCapFailurePropagatesAs413() {
        var overCapReader = new FailingReader(new SinkException(
                io.osproxy.core.ErrorCode.UPSTREAM_FAILED, "wrapped",
                new io.osproxy.core.BodyTooLargeException("over cap")));
        var pipeline = new Pipeline(new TenancyRouter(dedicatedIndexSpi()), sink, overCapReader);
        var ctx = request(HttpMethod.POST, "/orders/_search", "acme", new byte[0]);
        PipelineResponse resp = org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                pipeline.searchStreaming(ctx, new ByteArrayInputStream(
                        "{\"query\":{\"match_all\":{}}}".getBytes(StandardCharsets.UTF_8)), true));
        assertThat(resp.status()).isEqualTo(413);
    }

    /** A Reader whose search/count-streaming calls always throw a canned failure. */
    private static final class FailingReader implements Reader {
        private final SinkException failure;

        FailingReader(SinkException failure) {
            this.failure = failure;
        }

        @Override
        public Response get(Target target, String physicalId, Optional<String> routing) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Response search(Target target, byte[] body) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Response count(Target target, byte[] body) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Response searchStreaming(Target target, InputStream body) throws SinkException {
            throw failure;
        }

        @Override
        public Response countStreaming(Target target, InputStream body) throws SinkException {
            throw failure;
        }
    }
}
