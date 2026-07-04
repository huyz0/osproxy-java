package io.osproxy.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class RulesTest {

    @Test
    void docIdRuleDetectsPartitionReference() {
        assertThat(new DocIdRule("{partition}:{id}", true).referencesPartition()).isTrue();
        assertThat(new DocIdRule("{id}", false).referencesPartition()).isFalse();
        assertThatThrownBy(() -> new DocIdRule("{partition}", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anyOfRequiresAtLeastOneSource() {
        assertThatThrownBy(() -> new PartitionKeySpec.AnyOf(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        var spec = new PartitionKeySpec.AnyOf(List.of(
                new PartitionKeySpec.Header("x-tenant"),
                new PartitionKeySpec.PrincipalAttr("tenant")));
        assertThat(spec.sources()).hasSize(2);
    }

    @Test
    void injectedValueVariantsValidate() {
        assertThatThrownBy(() -> new InjectedValue.Constant(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InjectedValue.FromHeader(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new InjectedValue.FromPrincipal("tenant").attribute()).isEqualTo("tenant");
    }
}
