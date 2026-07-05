package io.osproxy.engine;

import static io.osproxy.engine.PipelineTestSupport.json;
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
import io.osproxy.spi.InjectedField;
import io.osproxy.spi.InjectedValue;
import io.osproxy.spi.PartitionKeySpec;
import io.osproxy.spi.Placement;
import io.osproxy.spi.PlacementAt;
import io.osproxy.spi.RequestCtx.HttpMethod;
import io.osproxy.spi.TenancySpi;
import io.osproxy.tenancy.TenancyRouter;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Edge paths: every injected-value source, bulk stale-epoch, upstream failure. */
class PipelineEdgeTest {

    /** A tenancy injecting from every value source at once. */
    private static TenancySpi allSourcesSpi() {
        var placement = new Placement.SharedIndex(
                new ClusterId("c1"), new IndexName("shared"),
                List.of(
                        new InjectedField("_tenant", InjectedValue.PartitionIdValue.INSTANCE),
                        new InjectedField("_env", new InjectedValue.Constant("\"prod\"")),
                        new InjectedField("_who", new InjectedValue.FromPrincipal("tenant")),
                        new InjectedField("_via", new InjectedValue.FromHeader("x-tenant"))));
        return new TenancySpi() {
            @Override
            public PartitionKeySpec partitionKeySpec() {
                return new PartitionKeySpec.Header("x-tenant");
            }

            @Override
            public PlacementAt placementFor(PartitionId partition) {
                return new PlacementAt(placement, Epoch.INITIAL);
            }
        };
    }

    @Test
    void everyInjectedValueSourceResolvesAndStripsSymmetrically() {
        var sink = new MemorySink();
        var pipeline = new Pipeline(new TenancyRouter(allSourcesSpi()), sink, sink);

        assertThat(pipeline.handle(request(
                        HttpMethod.PUT, "/orders/_doc/1", "acme", "{\"m\":1}".getBytes()))
                .status()).isEqualTo(201);

        var got = json(pipeline.handle(request(HttpMethod.GET, "/orders/_doc/1", "acme")));
        // All four injected markers are stripped on read.
        for (String field : List.of("_tenant", "_env", "_who", "_via")) {
            assertThat(got.at("/_source/" + field).isMissingNode()).isTrue();
        }
        assertThat(got.at("/_source/m").intValue()).isEqualTo(1);
    }

    @Test
    void missingPrincipalAttrFailsThe400Way() {
        var placement = new Placement.SharedIndex(
                new ClusterId("c1"), new IndexName("shared"),
                List.of(new InjectedField("_who", new InjectedValue.FromPrincipal("absent"))));
        TenancySpi spi = new TenancySpi() {
            @Override
            public PartitionKeySpec partitionKeySpec() {
                return new PartitionKeySpec.Header("x-tenant");
            }

            @Override
            public PlacementAt placementFor(PartitionId partition) {
                return new PlacementAt(placement, Epoch.INITIAL);
            }
        };
        var sink = new MemorySink();
        var pipeline = new Pipeline(new TenancyRouter(spi), sink, sink);
        var resp = pipeline.handle(request(
                HttpMethod.PUT, "/orders/_doc/1", "acme", "{}".getBytes()));
        assertThat(resp.status()).isEqualTo(400);
        assertThat(new String(resp.body())).contains("partition_unresolved");
    }

    @Test
    void bulkUnderAWriteFreezeReportsStaleEpochPerItemNotAsAWholeBatchFailure() {
        // A per-item condition (stale epoch) must not abort the whole batch
        // — real OpenSearch, and this endpoint's own streaming twin, both
        // report bulk failures per item. The response itself is still 200,
        // with the failing item's own status/result carrying the 409.
        var sink = new MemorySink();
        var pipeline = new Pipeline(
                new TenancyRouter(PipelineTestSupport.sharedIndexSpi(false)), sink, sink);
        var resp = pipeline.handle(request(
                HttpMethod.POST, "/_bulk", "acme",
                "{\"index\":{\"_index\":\"orders\",\"_id\":\"1\"}}\n{}\n".getBytes()));
        assertThat(resp.status()).isEqualTo(200);
        JsonNode body = json(resp);
        assertThat(body.get("errors").asBoolean()).isTrue();
        JsonNode item = body.at("/items/0/index");
        assertThat(item.get("status").asInt()).isEqualTo(409);
        assertThat(item.get("result").asText()).isEqualTo("error");
    }

    @Test
    void upstreamSearchFailureMapsToUpstreamFailed() {
        var sink = new MemorySink();
        Reader failing = new Reader() {
            @Override
            public Response get(Target t, String id, Optional<String> routing) {
                return new Response(500, new byte[0]);
            }

            @Override
            public Response search(Target t, byte[] body) {
                return new Response(503, new byte[0]);
            }

            @Override
            public Response count(Target t, byte[] body) throws SinkException {
                throw new SinkException(
                        io.osproxy.core.ErrorCode.UPSTREAM_FAILED, "boom");
            }
        };
        var pipeline = new Pipeline(
                new TenancyRouter(PipelineTestSupport.sharedIndexSpi(true)), sink, failing);

        assertThat(pipeline.handle(request(HttpMethod.POST, "/orders/_search", "acme"))
                .status()).isEqualTo(502);
        assertThat(pipeline.handle(request(HttpMethod.POST, "/orders/_count", "acme"))
                .status()).isEqualTo(502);
        // A non-404 get error is upstream's fault, never reshaped into a 400.
        var got = pipeline.handle(request(HttpMethod.GET, "/orders/_doc/1", "acme"));
        assertThat(got.status()).isEqualTo(502);
    }
}
