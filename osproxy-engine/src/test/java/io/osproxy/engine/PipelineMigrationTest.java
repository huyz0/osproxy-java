package io.osproxy.engine;

import static io.osproxy.engine.PipelineTestSupport.json;
import static io.osproxy.engine.PipelineTestSupport.request;
import static org.assertj.core.api.Assertions.assertThat;

import io.osproxy.core.ClusterId;
import io.osproxy.core.IndexName;
import io.osproxy.core.PartitionId;
import io.osproxy.sink.MemorySink;
import io.osproxy.spi.DocIdRule;
import io.osproxy.spi.PartitionKeySpec;
import io.osproxy.spi.Placement;
import io.osproxy.spi.PlacementAt;
import io.osproxy.spi.RequestCtx.HttpMethod;
import io.osproxy.spi.TenancySpi;
import io.osproxy.tenancy.MigrationControl;
import io.osproxy.tenancy.MigrationGatedTenancy;
import io.osproxy.tenancy.PlacementTable;
import io.osproxy.tenancy.TenancyRouter;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** INV-M4 live: the pipeline's write paths are gated, its read paths never. */
class PipelineMigrationTest {

    private static final PartitionId ACME = new PartitionId("acme");

    /** The delegate provides resolution + rules; placements come from the table. */
    private static TenancySpi resolutionOnly() {
        return new TenancySpi() {
            @Override
            public PartitionKeySpec partitionKeySpec() {
                return new PartitionKeySpec.Header("x-tenant");
            }

            @Override
            public PlacementAt placementFor(PartitionId partition) {
                throw new UnsupportedOperationException("gated tenancy uses the table");
            }

            @Override
            public Optional<DocIdRule> docIdRule() {
                return Optional.of(new DocIdRule("{partition}:{id}", true));
            }
        };
    }

    @Test
    void writesAreHeldDuringCutoverAndResumeAfter() {
        var table = new PlacementTable();
        table.put(ACME, new Placement.SharedIndex(
                new ClusterId("c1"), new IndexName("shared"), PipelineTestSupport.INJECT));
        var control = new MigrationControl(table);
        var sink = new MemorySink();
        var pipeline = new Pipeline(
                new TenancyRouter(new MigrationGatedTenancy(resolutionOnly(), table, control)),
                sink, sink);

        // Settled: write + read work.
        assertThat(pipeline.handle(request(
                        HttpMethod.PUT, "/orders/_doc/1", "acme", "{\"m\":1}".getBytes()))
                .status()).isEqualTo(201);

        // Draining: writes still admitted.
        control.beginDrain(ACME);
        assertThat(pipeline.handle(request(
                        HttpMethod.PUT, "/orders/_doc/2", "acme", "{}".getBytes()))
                .status()).isEqualTo(201);

        // Cutover to a new shared index: every write path answers 409...
        control.cutover(ACME, new Placement.SharedIndex(
                new ClusterId("c1"), new IndexName("shared-v2"), PipelineTestSupport.INJECT));
        assertThat(pipeline.handle(request(
                        HttpMethod.PUT, "/orders/_doc/3", "acme", "{}".getBytes()))
                .status()).isEqualTo(409);
        assertThat(pipeline.handle(request(
                        HttpMethod.DELETE, "/orders/_doc/1", "acme"))
                .status()).isEqualTo(409);
        // Bulk reports the stale epoch per item, not as a whole-batch
        // failure — the response is still 200, with the item's own status
        // carrying the 409.
        var bulkResp = pipeline.handle(request(
                HttpMethod.POST, "/_bulk", "acme",
                "{\"index\":{\"_index\":\"orders\",\"_id\":\"4\"}}\n{}\n".getBytes()));
        assertThat(bulkResp.status()).isEqualTo(200);
        assertThat(json(bulkResp).at("/items/0/index/status").asInt()).isEqualTo(409);

        // ...while reads keep answering (INV-M4). The new placement is what
        // resolution sees, so the read goes to the (empty) new index — gated
        // migration changes where reads land, never whether they run.
        assertThat(pipeline.handle(request(HttpMethod.GET, "/orders/_doc/1", "acme"))
                .status()).isEqualTo(404);
        assertThat(pipeline.handle(request(
                        HttpMethod.POST, "/orders/_search", "acme", new byte[0]))
                .status()).isEqualTo(200);

        // Complete: writes resume at the new epoch, into the new index.
        control.complete(ACME);
        assertThat(pipeline.handle(request(
                        HttpMethod.PUT, "/orders/_doc/5", "acme", "{}".getBytes()))
                .status()).isEqualTo(201);
        assertThat(pipeline.handle(request(HttpMethod.GET, "/orders/_doc/5", "acme"))
                .status()).isEqualTo(200);
    }
}
