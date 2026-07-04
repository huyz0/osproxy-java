package io.osproxy.core;

/**
 * The tenancy partition a request resolved to (a tenant, a shard key — the
 * SPI decides its meaning). Opaque to the proxy beyond equality; it is
 * embedded in constructed doc ids and injected fields, so it must be
 * non-empty and free of the id-rule separator's ambiguity (validated where
 * the rule applies, not here).
 */
public record PartitionId(String value) {
    public PartitionId {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("partition id must be non-empty");
        }
    }
}
