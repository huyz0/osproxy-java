package io.osproxy.spi;

import java.util.List;

/**
 * Where the partition key is found in a request. {@link AnyOf} tries each
 * source in order and uses the first that yields a value.
 */
public sealed interface PartitionKeySpec {

    /** A dotted path into the request body, e.g. {@code "customer.tenant"}. */
    record BodyField(String path) implements PartitionKeySpec {
        public BodyField {
            if (path == null || path.isEmpty()) {
                throw new IllegalArgumentException("body path must be non-empty");
            }
        }
    }

    /** A request header (case-insensitive lookup). */
    record Header(String name) implements PartitionKeySpec {
        public Header {
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("header name must be non-empty");
            }
        }
    }

    /** An attribute of the authenticated principal. */
    record PrincipalAttr(String attribute) implements PartitionKeySpec {
        public PrincipalAttr {
            if (attribute == null || attribute.isEmpty()) {
                throw new IllegalArgumentException("attribute name must be non-empty");
            }
        }
    }

    /** The first source that yields a value wins; empty list is invalid. */
    record AnyOf(List<PartitionKeySpec> sources) implements PartitionKeySpec {
        public AnyOf {
            if (sources == null || sources.isEmpty()) {
                throw new IllegalArgumentException("anyOf needs at least one source");
            }
            sources = List.copyOf(sources);
        }
    }
}
