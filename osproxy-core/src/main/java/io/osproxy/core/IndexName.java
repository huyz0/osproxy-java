package io.osproxy.core;

/**
 * A physical or logical index name. Validation mirrors OpenSearch's own
 * rules loosely (non-empty, lowercase, no path separators) — enough to make
 * an injected name safe to place in a URL path.
 */
public record IndexName(String value) {
    public IndexName {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("index name must be non-empty");
        }
        if (value.contains("/") || value.contains("\\")) {
            throw new IllegalArgumentException("index name must not contain path separators");
        }
        if (!value.equals(value.toLowerCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException("index name must be lowercase");
        }
    }
}
