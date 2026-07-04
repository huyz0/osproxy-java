package io.osproxy.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.osproxy.core.ClusterId;
import io.osproxy.core.IndexName;
import io.osproxy.core.PartitionId;
import io.osproxy.spi.Placement;
import io.osproxy.spi.SpiException;
import org.junit.jupiter.api.Test;

class PlacementTableTest {

    private static final PartitionId ACME = new PartitionId("acme");

    @Test
    void replacingAPlacementBumpsTheEpoch() throws Exception {
        var table = new PlacementTable();
        var a = new Placement.DedicatedIndex(new ClusterId("c1"), new IndexName("a"));
        var b = new Placement.DedicatedIndex(new ClusterId("c2"), new IndexName("b"));

        table.put(ACME, a);
        assertThat(table.lookup(ACME).epoch().generation()).isZero();
        table.put(ACME, b);
        var at = table.lookup(ACME);
        assertThat(at.epoch().generation()).isEqualTo(1);
        assertThat(at.placement()).isEqualTo(b);
    }

    @Test
    void unknownPartitionFallsBackToDefaultOrFailsClosed() throws Exception {
        var table = new PlacementTable();
        assertThatThrownBy(() -> table.lookup(ACME))
                .isInstanceOf(SpiException.PlacementMissing.class);

        var shared = new Placement.DedicatedCluster(new ClusterId("shared"));
        table.setDefault(shared);
        assertThat(table.lookup(ACME).placement()).isEqualTo(shared);
    }

    @Test
    void snapshotListsOnlyExplicitEntries() {
        var table = new PlacementTable();
        table.setDefault(new Placement.DedicatedCluster(new ClusterId("d")));
        table.put(ACME, new Placement.DedicatedCluster(new ClusterId("c")));
        assertThat(table.snapshot()).containsOnlyKeys(ACME);
    }
}
