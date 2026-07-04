package io.osproxy.core;

/**
 * Identifies one upstream OpenSearch cluster. A distinct type (not a bare
 * string) so a cluster id can never be confused with an index name or a
 * partition id at a call site.
 */
public record ClusterId(String value) {
    public ClusterId {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("cluster id must be non-empty");
        }
    }
}
