package io.osproxy.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.osproxy.core.ClusterId;
import io.osproxy.core.EndpointKind;
import io.osproxy.core.Epoch;
import io.osproxy.core.IndexName;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The fail-fast null/empty guards on every record's compact constructor. */
class ValidationTest {

    @Test
    void bodyFieldSpecValidatesItsPath() {
        assertThat(new PartitionKeySpec.BodyField("customer.tenant").path())
                .isEqualTo("customer.tenant");
        assertThatThrownBy(() -> new PartitionKeySpec.BodyField(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PartitionKeySpec.Header(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PartitionKeySpec.PrincipalAttr(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructIdCarriesItsRule() {
        var rule = new DocIdRule("{partition}:{id}", false);
        assertThat(new BodyTransform.ConstructId(rule).rule()).isEqualTo(rule);
    }

    @Test
    void recordsRejectNulls() {
        assertThatThrownBy(() -> new PlacementAt(null, Epoch.INITIAL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Principal(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InjectedField("", InjectedValue.PartitionIdValue.INSTANCE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InjectedField("_t", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RequestCtx(
                        null, "/", EndpointKind.SEARCH, Optional.empty(), Optional.empty(),
                        List.of(), new byte[0], new Principal("a")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exceptionAccessorsExposeTheirDiagnostics() {
        assertThat(new SpiException.PlacementBackend(false).retryable()).isFalse();
        assertThat(new SpiException.UnsupportedEndpoint(EndpointKind.ADMIN).endpoint())
                .isEqualTo(EndpointKind.ADMIN);
    }

    @Test
    void placementClusterSwitchCoversEveryMode() {
        var c = new ClusterId("c9");
        var idx = new IndexName("i");
        List<Placement> all = List.of(
                new Placement.DedicatedCluster(c),
                new Placement.DedicatedIndex(c, idx),
                new Placement.SharedIndex(c, idx, List.of(
                        new InjectedField("_t", new InjectedValue.Constant("\"x\"")))));
        for (Placement p : all) {
            assertThat(p.cluster()).isEqualTo(c);
        }
    }

    @Test
    void principalWithAttributesCopiesTheMap() {
        var attrs = new java.util.HashMap<String, String>();
        attrs.put("tenant", "acme");
        var p = new Principal("alice", attrs);
        attrs.clear();
        assertThat(p.attribute("tenant")).contains("acme");
        assertThat(Map.copyOf(p.attributes())).containsEntry("tenant", "acme");
    }
}
