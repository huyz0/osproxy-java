package io.osproxy.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ForwardPolicyTest {

    private static List<Map.Entry<String, String>> raw() {
        return List.of(
                Map.entry("Authorization", "Bearer s3cret"),
                Map.entry("X-Tenant", "acme"),
                Map.entry("traceparent", "00-abc-def-01"),
                Map.entry("b3", "abc-def-1"),
                Map.entry("Connection", "keep-alive"),
                Map.entry("Host", "client.local"),
                Map.entry("Content-Length", "42"),
                Map.entry("Accept-Encoding", "gzip"));
    }

    private static List<String> names(List<Map.Entry<String, String>> set) {
        return set.stream().map(e -> e.getKey().toLowerCase()).toList();
    }

    @Test
    void passAllForwardsClientHeadersMinusHopByHopAndFraming() {
        List<String> n = names(ForwardPolicy.passAll().forwardSet(raw()));
        assertThat(n).contains("authorization", "x-tenant", "traceparent", "b3");
        assertThat(n).doesNotContain("connection", "host", "content-length", "accept-encoding");
    }

    @Test
    void theDenyListDropsNamedHeadersCaseInsensitively() {
        var policy = new ForwardPolicy(true, List.of("AUTHORIZATION"));
        List<String> n = names(policy.forwardSet(raw()));
        assertThat(n).doesNotContain("authorization");
        assertThat(n).contains("x-tenant");
    }

    @Test
    void disabledForwardsNothing() {
        assertThat(ForwardPolicy.disabled().forwardSet(raw())).isEmpty();
        assertThat(new ForwardPolicy(false, List.of("x")).forwardSet(raw())).isEmpty();
    }
}
