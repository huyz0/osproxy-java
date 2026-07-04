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

    @Test
    void migrationGatedTenancyDelegatesEverythingButPlacementAndGate() throws Exception {
        var table = new PlacementTable();
        table.put(ACME, new Placement.DedicatedIndex(new ClusterId("c1"), new IndexName("i")));
        var control = new MigrationControl(table);
        io.osproxy.spi.TenancySpi delegate = new io.osproxy.spi.TenancySpi() {
            @Override
            public io.osproxy.spi.PartitionKeySpec partitionKeySpec() {
                return new io.osproxy.spi.PartitionKeySpec.Header("x-tenant");
            }

            @Override
            public io.osproxy.spi.PlacementAt placementFor(io.osproxy.core.PartitionId p) {
                throw new UnsupportedOperationException();
            }

            @Override
            public java.util.Optional<io.osproxy.spi.DocIdRule> docIdRule() {
                return java.util.Optional.of(new io.osproxy.spi.DocIdRule("{partition}:{id}", true));
            }

            @Override
            public java.util.List<io.osproxy.spi.InjectedField> injectedFields() {
                return java.util.List.of();
            }

            @Override
            public java.util.Optional<String> clusterEndpoint(io.osproxy.core.ClusterId c) {
                return java.util.Optional.of("http://x");
            }
        };
        var gated = new MigrationGatedTenancy(delegate, table, control);
        assertThat(gated.partitionKeySpec())
                .isInstanceOf(io.osproxy.spi.PartitionKeySpec.Header.class);
        assertThat(gated.docIdRule()).isPresent();
        assertThat(gated.injectedFields()).isEmpty();
        assertThat(gated.clusterEndpoint(new ClusterId("c1"))).contains("http://x");
        // Placement comes from the table, not the delegate.
        assertThat(gated.placementFor(ACME).epoch().generation()).isZero();
        assertThat(gated.admitWrite(ACME, gated.placementFor(ACME).epoch())).isTrue();
    }
}
