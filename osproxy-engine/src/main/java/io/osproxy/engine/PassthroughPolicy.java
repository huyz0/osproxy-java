package io.osproxy.engine;

import io.osproxy.core.ClusterId;
import io.osproxy.spi.RequestCtx;
import java.util.List;
import java.util.Optional;

/**
 * Tenant-agnostic passthrough: forward a request verbatim to one cluster.
 *
 * <p>When set on a {@link Pipeline} and {@link #matches} a request, the
 * pipeline skips tenancy entirely (no partition resolve, no body rewrite, no
 * isolation) and forwards the raw request to the configured cluster,
 * returning the upstream response unchanged.
 *
 * <p>The match is per request, by logical index, so one proxy serves both
 * modes at once: list the indices that are not (yet) onboarded into tenancy
 * and those flow through verbatim, while everything else is tenant-isolated.
 * This is the composable migration shape — legacy indices pass through,
 * tenanted indices do not, not a global "isolation off" switch. It is
 * fail-closed: an index that does not match keeps full tenancy. Matching is
 * on the operator-configured index list only, never a client-supplied
 * header, so a client cannot opt itself out of isolation. An empty match
 * list means every request passes through (the whole-instance
 * transparent/capture proxy).
 *
 * @param cluster the cluster a matching request is forwarded to
 * @param endpoint the cluster's base URL, when set (the sink pools it like
 *     any endpoint)
 * @param indexPrefixes logical-index prefixes that route verbatim; empty
 *     means every request passes through
 */
public record PassthroughPolicy(ClusterId cluster, Optional<String> endpoint, List<String> indexPrefixes) {

    public PassthroughPolicy {
        indexPrefixes = List.copyOf(indexPrefixes);
    }

    /**
     * A policy forwarding every request to {@code cluster} at {@code
     * endpoint} (the whole-instance transparent proxy). Use {@link
     * #withIndexPrefixes} to pass through only selected indices and
     * tenant-isolate the rest.
     */
    public static PassthroughPolicy of(ClusterId cluster, String endpoint) {
        return new PassthroughPolicy(cluster, Optional.of(endpoint), List.of());
    }

    /**
     * Restricts passthrough to requests whose logical index starts with one
     * of {@code prefixes}; all other requests keep full tenancy. An empty
     * list (the default) passes everything through.
     */
    public PassthroughPolicy withIndexPrefixes(List<String> prefixes) {
        return new PassthroughPolicy(cluster, endpoint, prefixes);
    }

    /**
     * Whether a request for {@code logicalIndex} should be forwarded
     * verbatim. Matches when no prefixes are configured (whole-instance
     * passthrough) or the request's logical index starts with a configured
     * prefix; otherwise the request stays tenanted.
     */
    public boolean matchesIndex(String logicalIndex) {
        if (indexPrefixes.isEmpty()) {
            return true;
        }
        for (String prefix : indexPrefixes) {
            if (logicalIndex.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code ctx} should be forwarded verbatim. */
    public boolean matches(RequestCtx ctx) {
        return matchesIndex(ctx.logicalIndex().orElse(""));
    }

    /** The sink target a matching request forwards to. */
    public io.osproxy.core.Target target() {
        return new io.osproxy.core.Target(
                cluster, new io.osproxy.core.IndexName("passthrough"), endpoint);
    }
}
