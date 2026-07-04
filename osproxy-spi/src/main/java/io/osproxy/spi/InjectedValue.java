package io.osproxy.spi;

/** Where an injected field's value comes from, resolved per request. */
public sealed interface InjectedValue {

    /** The resolved partition id (the common case: a tenant marker). */
    record PartitionIdValue() implements InjectedValue {
        public static final PartitionIdValue INSTANCE = new PartitionIdValue();
    }

    /** A constant JSON scalar (stored as its literal JSON representation). */
    record Constant(String jsonLiteral) implements InjectedValue {
        public Constant {
            if (jsonLiteral == null || jsonLiteral.isEmpty()) {
                throw new IllegalArgumentException("constant literal must be non-empty");
            }
        }
    }

    /** A named principal attribute (fails the request if absent). */
    record FromPrincipal(String attribute) implements InjectedValue {
        public FromPrincipal {
            if (attribute == null || attribute.isEmpty()) {
                throw new IllegalArgumentException("attribute name must be non-empty");
            }
        }
    }

    /** A named request header (fails the request if absent). */
    record FromHeader(String header) implements InjectedValue {
        public FromHeader {
            if (header == null || header.isEmpty()) {
                throw new IllegalArgumentException("header name must be non-empty");
            }
        }
    }
}
