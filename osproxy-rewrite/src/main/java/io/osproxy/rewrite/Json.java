package io.osproxy.rewrite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * The module's one shared {@link ObjectMapper} and the scalar-at-path
 * extraction used by id templates and partition resolution.
 */
public final class Json {

    /** Shared, immutable-configured mapper (thread-safe). */
    public static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {}

    /** Parses a JSON object from bytes, refusing anything else. */
    public static ObjectNode parseObject(byte[] body) throws RewriteException {
        JsonNode node;
        try {
            node = MAPPER.readTree(body);
        } catch (IOException e) {
            throw new RewriteException(RewriteException.Kind.INVALID_JSON, "invalid json body");
        }
        if (!(node instanceof ObjectNode obj)) {
            throw new RewriteException(RewriteException.Kind.NOT_AN_OBJECT, "body must be a json object");
        }
        return obj;
    }

    /** Serializes a node back to bytes. */
    public static byte[] writeBytes(JsonNode node) {
        try {
            return MAPPER.writeValueAsBytes(node);
        } catch (IOException e) {
            throw new UncheckedIOException(e); // serializing an in-memory tree cannot fail
        }
    }

    /**
     * Extracts the scalar at a dotted {@code path} (e.g. {@code customer.id}).
     * Numbers and booleans render as their JSON literal; strings as their value.
     */
    public static String extractScalar(JsonNode doc, String path) throws RewriteException {
        JsonNode node = doc;
        for (String segment : path.split("\\.")) {
            node = node.get(segment);
            if (node == null) {
                throw new RewriteException(
                        RewriteException.Kind.PATH_NOT_SCALAR, "path not found: " + path);
            }
        }
        if (!node.isValueNode() || node.isNull()) {
            throw new RewriteException(
                    RewriteException.Kind.PATH_NOT_SCALAR, "path not a scalar: " + path);
        }
        return node.isTextual() ? node.textValue() : node.toString();
    }
}
