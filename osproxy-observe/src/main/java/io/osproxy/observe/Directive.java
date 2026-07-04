package io.osproxy.observe;

import io.osproxy.core.EndpointKind;
import java.util.Optional;

/**
 * One diagnostics directive: raise (or silence) the recorded level for the
 * requests it matches, until it expires. Matching attributes are optional —
 * an absent attribute matches everything. Sampling is deterministic per
 * request id, so a directive with {@code samplePerMille=10} records the
 * same 1% of requests on every instance that sees them.
 *
 * @param id operator-chosen identifier (introspection/round-trip)
 * @param level the level applied to matching requests
 * @param tenant match only this tenant, when present
 * @param index match only this logical index, when present
 * @param endpoint match only this endpoint kind, when present
 * @param principal match only this authenticated principal id, when present
 * @param samplePerMille 0..1000; 1000 = every matching request
 * @param ringBuffer when true, a matching request's explanation is also
 *     captured into the break-glass tape (operator turns this on
 *     deliberately when failing ids aren't known up front)
 * @param expiresAtNanos absolute monotonic deadline (from the proxy Clock)
 */
public record Directive(
        String id,
        DiagLevel level,
        Optional<String> tenant,
        Optional<String> index,
        Optional<EndpointKind> endpoint,
        Optional<String> principal,
        int samplePerMille,
        boolean ringBuffer,
        long expiresAtNanos) {

    public Directive {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("directive id must be non-empty");
        }
        if (samplePerMille < 0 || samplePerMille > 1000) {
            throw new IllegalArgumentException("samplePerMille must be 0..1000");
        }
    }

    /** The request attributes a directive matches against — all shape-level. */
    public record RequestAttrs(
            String tenant, Optional<String> index, EndpointKind endpoint, String principal) {}

    /** Whether this directive applies to a request at {@code nowNanos}. */
    public boolean matches(RequestAttrs attrs, String requestId, long nowNanos) {
        if (nowNanos >= expiresAtNanos) {
            return false;
        }
        if (tenant.isPresent() && !tenant.get().equals(attrs.tenant())) {
            return false;
        }
        if (index.isPresent() && !index.equals(attrs.index())) {
            return false;
        }
        if (endpoint.isPresent() && endpoint.get() != attrs.endpoint()) {
            return false;
        }
        if (principal.isPresent() && !principal.get().equals(attrs.principal())) {
            return false;
        }
        // Deterministic sampling: the same request id always lands in the
        // same bucket.
        int bucket = Math.floorMod(requestId.hashCode(), 1000);
        return bucket < samplePerMille;
    }
}
