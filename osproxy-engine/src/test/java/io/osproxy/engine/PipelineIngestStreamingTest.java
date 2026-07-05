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
import io.osproxy.sink.MemorySink;
import io.osproxy.spi.DocIdRule;
import io.osproxy.spi.PartitionKeySpec;
import io.osproxy.spi.Placement;
import io.osproxy.spi.PlacementAt;
import io.osproxy.spi.RequestCtx.HttpMethod;
import io.osproxy.spi.TenancySpi;
import io.osproxy.tenancy.TenancyRouter;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@link Pipeline#ingestDocStreaming} driven directly, mirroring the ordinary
 * {@code ingestDoc} cases {@code PipelineDocTest} covers, plus the
 * eligibility gate ({@link Pipeline#supportsStreamingIngest}) that decides
 * whether {@code AppHandler} even offers this path.
 */
class PipelineIngestStreamingTest {

    @Test
    void referenceStyleTenancyIsStreamingEligible() {
        var pipeline = pipeline(new MemorySink());
        assertThat(pipeline.supportsStreamingIngest()).isTrue();
    }

    @Test
    void aBodyDerivedPartitionKeyIsNotStreamingEligible() {
        var spi = bodyDerivedSpi();
        var pipeline = new Pipeline(new TenancyRouter(spi), new MemorySink(), new MemorySink());
        assertThat(pipeline.supportsStreamingIngest()).isFalse();
    }

    @Test
    void streamsAnIndexAndReadsItBackIsolatedAndLabelClean() throws Exception {
        var sink = new MemorySink();
        var pipeline = pipeline(sink);
        var ctx = request(HttpMethod.PUT, "/orders/_doc/1", "acme", new byte[0]);

        PipelineResponse out = pipeline.ingestDocStreaming(
                ctx, new ByteArrayInputStream("{\"msg\":\"hi\"}".getBytes(StandardCharsets.UTF_8)));
        assertThat(out.status()).isEqualTo(201);

        JsonNode got = json(pipeline.handle(request(HttpMethod.GET, "/orders/_doc/1", "acme")));
        assertThat(got.at("/_source/msg").textValue()).isEqualTo("hi");
        assertThat(got.at("/_source/_tenant").isMissingNode()).isTrue();
    }

    @Test
    void aMalformedStreamedBodyIsRefused() {
        var pipeline = pipeline(new MemorySink());
        var ctx = request(HttpMethod.PUT, "/orders/_doc/1", "acme", new byte[0]);
        org.junit.jupiter.api.Assertions.assertThrows(
                io.osproxy.rewrite.RewriteException.class,
                () -> pipeline.ingestDocStreaming(
                        ctx, new ByteArrayInputStream("not json".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void aStaleEpochRefusesTheStreamedWrite() {
        var sink = new MemorySink();
        var pipeline = new Pipeline(
                new TenancyRouter(PipelineTestSupport.sharedIndexSpi(false)), sink, sink);
        var ctx = request(HttpMethod.PUT, "/orders/_doc/1", "acme", new byte[0]);
        PipelineResponse out = assertDoesNotThrow(() -> pipeline.ingestDocStreaming(
                ctx, new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8))));
        assertThat(out.status()).isEqualTo(409);
    }

    private static PipelineResponse assertDoesNotThrow(
            org.junit.jupiter.api.function.ThrowingSupplier<PipelineResponse> supplier) {
        return org.junit.jupiter.api.Assertions.assertDoesNotThrow(supplier);
    }

    private static TenancySpi bodyDerivedSpi() {
        return new TenancySpi() {
            @Override
            public PartitionKeySpec partitionKeySpec() {
                return new PartitionKeySpec.BodyField("tenant");
            }

            @Override
            public PlacementAt placementFor(PartitionId partition) {
                return new PlacementAt(
                        new Placement.SharedIndex(new ClusterId("c1"), new IndexName("shared"),
                                PipelineTestSupport.INJECT),
                        Epoch.INITIAL);
            }

            @Override
            public Optional<DocIdRule> docIdRule() {
                return Optional.of(new DocIdRule("{partition}:{id}", true));
            }
        };
    }
}
