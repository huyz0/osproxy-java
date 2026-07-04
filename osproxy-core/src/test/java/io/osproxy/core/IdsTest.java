package io.osproxy.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class IdsTest {

    @Test
    void idsRejectNullAndEmpty() {
        assertThatThrownBy(() -> new ClusterId("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClusterId(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PartitionId("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IndexName("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void indexNameRejectsPathSeparatorsAndUppercase() {
        assertThatThrownBy(() -> new IndexName("a/b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IndexName("a\\b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IndexName("Orders")).isInstanceOf(IllegalArgumentException.class);
        assertThat(new IndexName("orders-2026").value()).isEqualTo("orders-2026");
    }

    @Test
    void idsAreValueTypes() {
        assertThat(new ClusterId("c1")).isEqualTo(new ClusterId("c1"));
        assertThat(new PartitionId("acme")).isNotEqualTo(new PartitionId("globex"));
    }

    @Test
    void epochStartsAtZeroAndSaturates() {
        assertThat(Epoch.INITIAL.generation()).isZero();
        assertThat(Epoch.INITIAL.next().generation()).isEqualTo(1);
        Epoch max = new Epoch(Long.MAX_VALUE);
        assertThat(max.next()).isEqualTo(max);
        assertThatThrownBy(() -> new Epoch(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void targetCarriesClusterIndexAndOptionalEndpoint() {
        Target t = new Target(new ClusterId("c1"), new IndexName("orders"));
        assertThat(t.endpointOverride()).isEmpty();
        Target withEp = new Target(
                new ClusterId("c1"), new IndexName("orders"),
                java.util.Optional.of("http://os:9200"));
        assertThat(withEp.endpointOverride()).contains("http://os:9200");
    }
}
