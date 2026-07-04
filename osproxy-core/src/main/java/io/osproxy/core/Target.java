package io.osproxy.core;

import java.util.Optional;

/**
 * Where a request physically goes: one cluster, one index. Exactly one
 * target per request — the proxy never fans out synchronously (ADR-002).
 *
 * @param cluster the upstream cluster
 * @param index the physical index on that cluster
 * @param endpointOverride an explicit base URL for the cluster, when the
 *     routing decision carries one (otherwise the sink's configured endpoint
 *     for the cluster id applies)
 */
public record Target(ClusterId cluster, IndexName index, Optional<String> endpointOverride) {
    public Target {
        if (cluster == null || index == null || endpointOverride == null) {
            throw new IllegalArgumentException("target fields must be non-null");
        }
    }

    public Target(ClusterId cluster, IndexName index) {
        this(cluster, index, Optional.empty());
    }
}
