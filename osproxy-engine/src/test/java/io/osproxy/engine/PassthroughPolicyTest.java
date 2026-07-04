package io.osproxy.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.osproxy.core.ClusterId;
import java.util.List;
import org.junit.jupiter.api.Test;

class PassthroughPolicyTest {

    @Test
    void aPrefixFreePolicyPassesEveryRequestThrough() {
        var policy = PassthroughPolicy.of(new ClusterId("c"), "http://c:9200");
        assertThat(policy.matchesIndex("anything")).isTrue();
        assertThat(policy.matchesIndex("orders")).isTrue();
    }

    @Test
    void aPrefixPolicyPassesOnlyMatchingIndicesAndIsolatesTheRest() {
        var policy = PassthroughPolicy.of(new ClusterId("c"), "http://c:9200")
                .withIndexPrefixes(List.of("legacy-", "raw_"));
        assertThat(policy.matchesIndex("legacy-orders")).isTrue();
        assertThat(policy.matchesIndex("raw_events")).isTrue();
        assertThat(policy.matchesIndex("orders")).isFalse();
        assertThat(policy.matchesIndex("not-legacy-orders"))
                .as("prefix must anchor at the start, not match mid-string")
                .isFalse();
    }
}
