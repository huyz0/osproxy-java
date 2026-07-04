package io.osproxy.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.osproxy.core.ClusterId;
import io.osproxy.core.Epoch;
import io.osproxy.core.IndexName;
import io.osproxy.core.PartitionId;
import io.osproxy.core.Target;
import java.util.List;
import org.junit.jupiter.api.Test;

class TenancySpiDefaultsTest {

    /** A minimal SPI that overrides only the two required methods. */
    private static final TenancySpi MINIMAL = new TenancySpi() {
        @Override
        public PartitionKeySpec partitionKeySpec() {
            return new PartitionKeySpec.Header("x-tenant");
        }

        @Override
        public PlacementAt placementFor(PartitionId partition) {
            return new PlacementAt(
                    new Placement.DedicatedCluster(new ClusterId("c1")), Epoch.INITIAL);
        }
    };

    @Test
    void defaultsAreNoIdRuleNoInjectionAlwaysAdmitUnknownEndpoint() {
        assertThat(MINIMAL.docIdRule()).isEmpty();
        assertThat(MINIMAL.injectedFields()).isEmpty();
        assertThat(MINIMAL.admitWrite(new PartitionId("acme"), Epoch.INITIAL)).isTrue();
        assertThat(MINIMAL.clusterEndpoint(new ClusterId("c1"))).isEmpty();
    }

    @Test
    void routeDecisionRejectsNullFields() {
        var target = new Target(new ClusterId("c1"), new IndexName("orders"));
        assertThatThrownBy(() -> new RouteDecision(
                        target, null, BodyTransform.None.INSTANCE, Epoch.INITIAL))
                .isInstanceOf(IllegalArgumentException.class);
        var ok = new RouteDecision(
                target, new PartitionId("acme"), BodyTransform.None.INSTANCE, Epoch.INITIAL);
        assertThat(ok.transform()).isSameAs(BodyTransform.None.INSTANCE);
    }

    @Test
    void bodyTransformVariantsCopyTheirLists() {
        var fields = new java.util.ArrayList<InjectedField>();
        fields.add(new InjectedField("_t", InjectedValue.PartitionIdValue.INSTANCE));
        var inject = new BodyTransform.Inject(fields);
        var both = new BodyTransform.Both(fields, new DocIdRule("{partition}:{id}", true));
        fields.clear();
        assertThat(inject.fields()).hasSize(1);
        assertThat(both.fields()).hasSize(1);
        assertThat(both.rule().setRouting()).isTrue();
    }
}
