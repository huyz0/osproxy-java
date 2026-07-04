package io.osproxy.spi;

/**
 * A field written into every ingested document of a shared-index partition
 * and stripped from every read response — the write↔read symmetry that makes
 * shared-index isolation invisible to clients.
 */
public record InjectedField(String name, InjectedValue value) {
    public InjectedField {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("injected field name must be non-empty");
        }
        if (value == null) {
            throw new IllegalArgumentException("injected value must be non-null");
        }
    }
}
