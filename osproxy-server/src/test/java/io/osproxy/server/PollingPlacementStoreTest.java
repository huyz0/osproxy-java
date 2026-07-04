package io.osproxy.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.helidon.webserver.WebServer;
import io.osproxy.core.PartitionId;
import io.osproxy.spi.Placement;
import io.osproxy.tenancy.PlacementTable;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Fleet placements over HTTP pull: converge, epoch-bump only on real moves. */
class PollingPlacementStoreTest {

    private static final PartitionId ACME = new PartitionId("acme");

    @Test
    void theDecoderIsFailClosed() throws Exception {
        var decoded = PollingPlacementStore.decode("""
                {"partitions":{
                  "acme":{"mode":"dedicated-index","cluster":"c2","index":"acme-idx"},
                  "globex":{"mode":"shared-index","cluster":"c1","index":"shared"},
                  "initech":{"mode":"dedicated-cluster","cluster":"initech-c"}}}
                """.getBytes());
        assertThat(decoded).hasSize(3);
        assertThat(decoded.get(ACME))
                .isInstanceOf(Placement.DedicatedIndex.class);
        assertThat(((Placement.SharedIndex) decoded.get(new PartitionId("globex")))
                .inject()).hasSize(1);

        for (String bad : new String[] {
            "not json",
            "{\"partitions\":[]}",
            "{\"partitions\":{},\"extra\":1}",
            "{\"partitions\":{\"a\":{\"mode\":\"sharded\",\"cluster\":\"c\"}}}",
            "{\"partitions\":{\"a\":{\"mode\":\"dedicated-index\",\"cluster\":\"c\"}}}",
            "{\"partitions\":{\"a\":{\"mode\":\"dedicated-cluster\"}}}",
            "{\"partitions\":{\"a\":{\"mode\":\"dedicated-cluster\",\"cluster\":\"c\",\"idx\":\"x\"}}}",
            "{\"partitions\":{\"a\":\"nope\"}}",
        }) {
            assertThatThrownBy(() -> PollingPlacementStore.decode(bad.getBytes()))
                    .isInstanceOf(PollingPlacementStore.InvalidPlacements.class);
        }
    }

    @Test
    void convergesAndBumpsEpochsOnlyOnRealMoves() throws Exception {
        AtomicReference<String> served = new AtomicReference<>("""
                {"partitions":{
                  "acme":{"mode":"dedicated-index","cluster":"c1","index":"a1"}}}
                """);
        WebServer source = WebServer.builder()
                .port(0)
                .routing(r -> r.get("/placements", (req, res) -> res.send(served.get())))
                .build()
                .start();
        var table = new PlacementTable();
        try (var store = new PollingPlacementStore(
                "http://localhost:" + source.port() + "/placements", table, 3_600_000)) {

            store.pollOnce();
            assertThat(table.lookup(ACME).epoch().generation()).isZero();
            assertThat(table.lookup(ACME).placement())
                    .isInstanceOf(Placement.DedicatedIndex.class);

            // Re-polling the identical document must not bump the epoch —
            // an epoch bump means the partition actually moved.
            store.pollOnce();
            store.pollOnce();
            assertThat(table.lookup(ACME).epoch().generation()).isZero();

            // A real move bumps once.
            served.set("""
                    {"partitions":{
                      "acme":{"mode":"dedicated-index","cluster":"c2","index":"a2"}}}
                    """);
            store.pollOnce();
            assertThat(table.lookup(ACME).epoch().generation()).isEqualTo(1);
            assertThat(table.lookup(ACME).placement().cluster().value()).isEqualTo("c2");

            // A malformed document is refused wholesale: nothing changes.
            served.set("{\"partitions\":{\"acme\":{\"mode\":\"bogus\",\"cluster\":\"c9\"}}}");
            store.pollOnce();
            assertThat(table.lookup(ACME).epoch().generation()).isEqualTo(1);
            assertThat(table.lookup(ACME).placement().cluster().value()).isEqualTo("c2");
        } finally {
            source.stop();
        }
    }
}
