package io.osproxy.server;

import io.osproxy.spi.Principal;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * Bearer-token authentication: token → tenant. Comparison is constant-time.
 * With no tokens configured the proxy runs in dev mode and trusts the
 * {@code x-tenant} header — convenient locally, never for production.
 */
public final class BearerAuth {

    private final Map<String, String> tokens;

    public BearerAuth(Map<String, String> tokens) {
        this.tokens = Map.copyOf(tokens);
    }

    /** Whether dev mode (no tokens) is active. */
    public boolean devMode() {
        return tokens.isEmpty();
    }

    /**
     * Authenticates a request from its Authorization header (and, in dev
     * mode, the x-tenant header). Empty means 401.
     */
    public Optional<Principal> authenticate(
            Optional<String> authorization, Optional<String> tenantHeader) {
        if (devMode()) {
            return tenantHeader.map(t -> new Principal("dev:" + t, Map.of("tenant", t)));
        }
        return authorization
                .filter(h -> h.regionMatches(true, 0, "Bearer ", 0, 7))
                .map(h -> h.substring(7).strip())
                .flatMap(this::lookup)
                .map(tenant -> new Principal("token:" + tenant, Map.of("tenant", tenant)));
    }

    /** Constant-time token comparison against every configured token. */
    private Optional<String> lookup(String presented) {
        byte[] presentedBytes = presented.getBytes(StandardCharsets.UTF_8);
        String matched = null;
        for (Map.Entry<String, String> entry : tokens.entrySet()) {
            byte[] candidate = entry.getKey().getBytes(StandardCharsets.UTF_8);
            if (MessageDigest.isEqual(candidate, presentedBytes)) {
                matched = entry.getValue();
            }
        }
        return Optional.ofNullable(matched);
    }

    /**
     * Whether {@code authorization} presents the single {@code expected}
     * bearer token — case-insensitive scheme, constant-time comparison. The
     * one shared implementation for every "check a bearer token against a
     * fixed secret" call site (e.g. the admin directives endpoint), so a
     * future fix here (a normalization change, a timing hardening) can't
     * land on one call site and miss another.
     */
    public static boolean matches(Optional<String> authorization, String expected) {
        return authorization
                .filter(h -> h.regionMatches(true, 0, "Bearer ", 0, 7))
                .map(h -> h.substring(7).strip())
                .map(t -> MessageDigest.isEqual(
                        t.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8)))
                .orElse(false);
    }
}
