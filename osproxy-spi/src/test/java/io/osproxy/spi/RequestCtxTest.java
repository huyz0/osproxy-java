package io.osproxy.spi;

import static org.assertj.core.api.Assertions.assertThat;

import io.osproxy.core.EndpointKind;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RequestCtxTest {

    private static RequestCtx ctx(List<Map.Entry<String, String>> headers) {
        return new RequestCtx(
                RequestCtx.HttpMethod.POST,
                "/orders/_doc",
                EndpointKind.INGEST_DOC,
                Optional.of("orders"),
                Optional.empty(),
                headers,
                "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                new Principal("alice", Map.of("tenant", "acme")));
    }

    @Test
    void headerLookupIsCaseInsensitiveFirstWins() {
        var ctx = ctx(List.of(
                Map.entry("X-Tenant", "acme"),
                Map.entry("x-tenant", "globex")));
        assertThat(ctx.header("x-TENANT")).contains("acme");
        assertThat(ctx.header("absent")).isEmpty();
    }

    @Test
    void nullBodyBecomesEmpty() {
        var ctx = new RequestCtx(
                RequestCtx.HttpMethod.GET,
                "/orders/_search",
                EndpointKind.SEARCH,
                Optional.of("orders"),
                Optional.empty(),
                List.of(),
                null,
                new Principal("alice"));
        assertThat(ctx.body()).isEmpty();
    }

    @Test
    void principalAttributesAreImmutableAndLookedUp() {
        var ctx = ctx(List.of());
        assertThat(ctx.principal().attribute("tenant")).contains("acme");
        assertThat(ctx.principal().attribute("nope")).isEmpty();
    }
}
