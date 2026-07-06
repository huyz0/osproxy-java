package io.osproxy.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.osproxy.core.ClusterId;
import io.osproxy.core.Epoch;
import io.osproxy.core.IndexName;
import io.osproxy.core.PartitionId;
import io.osproxy.sink.MemorySink;
import io.osproxy.sink.SinkException;
import io.osproxy.sink.WriteBatch;
import io.osproxy.spi.DocIdRule;
import io.osproxy.spi.InjectedField;
import io.osproxy.spi.InjectedValue;
import io.osproxy.spi.PartitionKeySpec;
import io.osproxy.spi.Placement;
import io.osproxy.spi.PlacementAt;
import io.osproxy.spi.RequestCtx.HttpMethod;
import io.osproxy.spi.TenancySpi;
import io.osproxy.tenancy.TenancyRouter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@code TenancySpi#routingHint}: a shared-index placement can route by
 * something other than the partition id itself, without touching the
 * mandatory isolation filter or the physical-id namespace, both of which
 * stay keyed on the partition either way.
 */
class PipelineRoutingHintTest {

    /** Records the routing value and physical id of every op handed to {@code write}. */
    private static final class RecordingSink implements io.osproxy.sink.Sink {
        final List<Optional<String>> routings = new ArrayList<>();
        final List<String> physicalIds = new ArrayList<>();

        @Override
        public WriteBatch.Ack write(List<WriteBatch.Op> ops) throws SinkException {
            for (WriteBatch.Op op : ops) {
                routings.add(op.op().routing());
                physicalIds.add(op.op().physicalId());
            }
            List<WriteBatch.OpResult> results = ops.stream()
                    .map(op -> new WriteBatch.OpResult(201, "created", op.op().physicalId()))
                    .toList();
            return new WriteBatch.Ack(results);
        }
    }

    /** A shared-index tenancy whose routing hint shards by an "even/odd" key. */
    private static TenancySpi shardingSpi(String hint) {
        var placement = new Placement.SharedIndex(
                new ClusterId("c1"), new IndexName("shared"),
                List.of(new InjectedField("_tenant", InjectedValue.PartitionIdValue.INSTANCE)));
        return new TenancySpi() {
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

            @Override
            public Optional<String> routingHint(PartitionId partition) {
                return Optional.ofNullable(hint);
            }
        };
    }

    @Test
    void aRoutingHintOverridesThePartitionAsTheOpenSearchRoutingValue() {
        var recorder = new RecordingSink();
        var pipeline = new Pipeline(
                new TenancyRouter(shardingSpi("shard-7")), recorder, new MemorySink());

        PipelineResponse resp = pipeline.handle(PipelineTestSupport.request(
                HttpMethod.PUT, "/orders/_doc/1", "acme", "{\"m\":\"x\"}".getBytes()));
        assertThat(resp.status()).isEqualTo(201);
        assertThat(recorder.routings).containsExactly(Optional.of("shard-7"));

        // The physical id namespace and isolation are untouched by the hint:
        // still partition-prefixed, never the routing value.
        assertThat(recorder.physicalIds).containsExactly("acme:1");
    }

    @Test
    void noHintFallsBackToThePartitionAsRoutingLikeBefore() {
        var recorder = new RecordingSink();
        var pipeline = new Pipeline(
                new TenancyRouter(shardingSpi(null)), recorder, new MemorySink());

        pipeline.handle(PipelineTestSupport.request(
                HttpMethod.PUT, "/orders/_doc/1", "acme", "{\"m\":\"x\"}".getBytes()));
        assertThat(recorder.routings).containsExactly(Optional.of("acme"));
    }
}
