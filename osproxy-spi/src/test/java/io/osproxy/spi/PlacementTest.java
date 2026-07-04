package io.osproxy.spi;

import static org.assertj.core.api.Assertions.assertThat;

import io.osproxy.core.ClusterId;
import io.osproxy.core.Epoch;
import io.osproxy.core.IndexName;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlacementTest {

    @Test
    void clusterIsExtractedForEveryMode() {
        var c = new ClusterId("c1");
        var idx = new IndexName("orders");
        assertThat(new Placement.DedicatedCluster(c).cluster()).isEqualTo(c);
        assertThat(new Placement.DedicatedIndex(c, idx).cluster()).isEqualTo(c);
        assertThat(new Placement.SharedIndex(c, idx, List.of()).cluster()).isEqualTo(c);
    }

    @Test
    void placementAtPairsEpochWithPlacement() {
        var at = new PlacementAt(
                new Placement.DedicatedCluster(new ClusterId("c1")), new Epoch(3));
        assertThat(at.epoch().generation()).isEqualTo(3);
    }

    @Test
    void sharedIndexInjectListIsImmutable() {
        var inject = new java.util.ArrayList<InjectedField>();
        inject.add(new InjectedField("_t", InjectedValue.PartitionIdValue.INSTANCE));
        var p = new Placement.SharedIndex(new ClusterId("c"), new IndexName("i"), inject);
        inject.clear();
        assertThat(p.inject()).hasSize(1);
    }
}
