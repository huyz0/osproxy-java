package io.osproxy.jmh;

import io.osproxy.core.ClusterId;
import io.osproxy.core.Epoch;
import io.osproxy.core.IndexName;
import io.osproxy.core.PartitionId;
import io.osproxy.core.Target;
import io.osproxy.engine.Pipeline;
import io.osproxy.sink.DocOp;
import io.osproxy.sink.MemorySink;
import io.osproxy.sink.WriteBatch;
import io.osproxy.spi.DocIdRule;
import io.osproxy.spi.InjectedField;
import io.osproxy.spi.InjectedValue;
import io.osproxy.spi.Placement;
import io.osproxy.spi.PlacementAt;
import io.osproxy.spi.PartitionKeySpec;
import io.osproxy.spi.Principal;
import io.osproxy.spi.RequestCtx;
import io.osproxy.spi.TenancySpi;
import io.osproxy.tenancy.TenancyRouter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * Response shaping — the read-path inverse of write-side injection: physical
 * id → logical, physical index → logical, injected fields stripped from
 * every hit's {@code _source} — is the one per-request cost the streaming
 * benches don't cover, since it only runs on the (always-buffered) response
 * side. Cost scales with hit count, the same way bulk cost scales with doc
 * count, so it's swept the same way.
 *
 * <pre>./gradlew :osproxy-jmh:jmh -Pjmh.includes=ResponseShaping</pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class ResponseShapingBench {

    @State(Scope.Benchmark)
    public static class SearchState {
        @Param({"1", "20", "200"})
        int hits;

        Pipeline pipeline;
        RequestCtx ctx;

        @Setup
        public void build() throws Exception {
            var placement = new Placement.SharedIndex(
                    new ClusterId("c1"), new IndexName("shared"),
                    List.of(new InjectedField("_tenant", InjectedValue.PartitionIdValue.INSTANCE)));
            TenancySpi spi = new TenancySpi() {
                @Override
                public PartitionKeySpec partitionKeySpec() {
                    return new PartitionKeySpec.Header("x-tenant");
                }

                @Override
                public PlacementAt placementFor(PartitionId partition) {
                    return new PlacementAt(placement, Epoch.INITIAL);
                }

                @Override
                public Optional<DocIdRule> docIdRule() {
                    return Optional.of(new DocIdRule("{partition}:{id}", true));
                }
            };
            var sink = new MemorySink();
            var target = new Target(new ClusterId("c1"), new IndexName("shared"));
            for (int i = 0; i < hits; i++) {
                sink.write(List.of(new WriteBatch.Op(
                        target,
                        new DocOp.Index("acme:doc-" + i, ("{\"msg\":\"hit " + i + "\"}").getBytes(),
                                Optional.empty()),
                        Epoch.INITIAL)));
            }
            pipeline = new Pipeline(new TenancyRouter(spi), sink, sink);
            ctx = new RequestCtx(
                    RequestCtx.HttpMethod.POST, "/orders/_search",
                    io.osproxy.core.EndpointKind.SEARCH,
                    Optional.of("orders"), Optional.empty(),
                    List.of(Map.entry("x-tenant", "acme")),
                    "{\"query\":{\"match_all\":{}}}".getBytes(),
                    new Principal("bench", Map.of("tenant", "acme")));
        }
    }

    @Benchmark
    public Object shapeSearchResponse(SearchState state) {
        return state.pipeline.handle(state.ctx);
    }
}
