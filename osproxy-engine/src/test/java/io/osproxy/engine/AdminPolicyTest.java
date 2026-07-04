package io.osproxy.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.osproxy.core.ClusterId;
import java.util.List;
import org.junit.jupiter.api.Test;

class AdminPolicyTest {

    @Test
    void allowsOnlyMatchingPrefixes() {
        var policy = AdminPolicy.of(new ClusterId("ops"), List.of("/_cat/"));
        assertThat(policy.allows("/_cat/health")).isTrue();
        assertThat(policy.allows("/_cat/")).isTrue();
        assertThat(policy.allows("/_cluster/settings")).isFalse();
    }

    @Test
    void anEmptyPrefixListAllowsNothing() {
        var policy = AdminPolicy.of(new ClusterId("ops"), List.of());
        assertThat(policy.allows("/_cat/health")).isFalse();
    }

    @Test
    void aDotDotSegmentIsNeverAllowedEvenUnderAnAllowedPrefix() {
        var policy = AdminPolicy.of(new ClusterId("ops"), List.of("/_cat/"));
        assertThat(policy.allows("/_cat/../_cluster/settings")).isFalse();
    }

    @Test
    void withEndpointSetsTheBaseUrl() {
        var policy = AdminPolicy.of(new ClusterId("ops"), List.of("/_cat/"))
                .withEndpoint("http://ops:9200");
        assertThat(policy.endpoint()).contains("http://ops:9200");
    }
}
