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
 * @param credentials the proxy's own upstream credential for this cluster,
 *     when {@link io.osproxy.spi.TenancySpi#upstreamCredentials} supplies
 *     one (otherwise the sink's default behavior applies: no credential
 *     header, or whatever the client's own forwarded headers carry)
 */
public record Target(
        ClusterId cluster,
        IndexName index,
        Optional<String> endpointOverride,
        Optional<UpstreamCredentials> credentials) {
    public Target {
        if (cluster == null || index == null || endpointOverride == null || credentials == null) {
            throw new IllegalArgumentException("target fields must be non-null");
        }
    }

    public Target(ClusterId cluster, IndexName index, Optional<String> endpointOverride) {
        this(cluster, index, endpointOverride, Optional.empty());
    }

    public Target(ClusterId cluster, IndexName index) {
        this(cluster, index, Optional.empty(), Optional.empty());
    }
}
