package io.osproxy.spi;

import java.util.Map;
import java.util.Optional;

/**
 * The authenticated identity a request acts as. Attributes are opaque
 * key/value pairs supplied by the authenticator (e.g. {@code tenant=acme})
 * that the SPI may use for partition resolution or field injection.
 */
public record Principal(String id, Map<String, String> attributes) {
    public Principal {
        if (id == null) {
            throw new IllegalArgumentException("principal id must be non-null");
        }
        attributes = Map.copyOf(attributes);
    }

    public Principal(String id) {
        this(id, Map.of());
    }

    /** Looks up an attribute by exact key. */
    public Optional<String> attribute(String key) {
        return Optional.ofNullable(attributes.get(key));
    }
}
