package io.osproxy.engine;

import io.osproxy.core.ClusterId;
import java.util.List;
import java.util.Optional;

/**
 * Administrative pass-through: {@code _cat}/{@code _cluster}/{@code _nodes}
 * requests carry no tenancy semantics, so the proxy cannot filter or strip
 * them. The only safe choices are refuse (the default, no policy set) or
 * pass through to an operator-allow-listed cluster, with the operator
 * accepting that admin output is cluster-wide, not tenant-scoped.
 *
 * @param cluster the cluster admin requests forward to
 * @param allowedPrefixes path prefixes permitted through (e.g. {@code
 *     "/_cat/"}); an empty list allows nothing
 * @param endpoint the admin cluster's base URL, when set; absent falls back
 *     to the sink's configured endpoint for {@code cluster}
 */
public record AdminPolicy(ClusterId cluster, List<String> allowedPrefixes, Optional<String> endpoint) {

    public AdminPolicy {
        allowedPrefixes = List.copyOf(allowedPrefixes);
    }

    /** A policy forwarding paths matching {@code allowedPrefixes} to {@code cluster}. */
    public static AdminPolicy of(ClusterId cluster, List<String> allowedPrefixes) {
        return new AdminPolicy(cluster, allowedPrefixes, Optional.empty());
    }

    /** Sets the admin cluster's base URL (builder style). */
    public AdminPolicy withEndpoint(String endpoint) {
        return new AdminPolicy(cluster, allowedPrefixes, Optional.of(endpoint));
    }

    /**
     * Whether {@code path} is allow-listed for pass-through. A path
     * containing a {@code ..} segment is never allowed: the prefix is an
     * authorization boundary, and upstream {@code ..} resolution would
     * otherwise let {@code /_cat/../_cluster/settings} slip past a
     * {@code /_cat/}-only allow-list.
     */
    public boolean allows(String path) {
        for (String segment : path.split("/")) {
            if (segment.equals("..")) {
                return false;
            }
        }
        for (String prefix : allowedPrefixes) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
