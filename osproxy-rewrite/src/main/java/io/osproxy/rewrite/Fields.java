package io.osproxy.rewrite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

/**
 * Injecting isolation fields on the write path and stripping them on the
 * read path — the two halves of the shared-index symmetry. Injection
 * overwrites a client-supplied field of the same name (a client must not be
 * able to spoof its partition marker).
 */
public final class Fields {

    private Fields() {}

    /** Sets each field on the document, overwriting client values. */
    public static void injectFields(ObjectNode doc, Map<String, JsonNode> fields) {
        for (Map.Entry<String, JsonNode> field : fields.entrySet()) {
            doc.set(field.getKey(), field.getValue());
        }
    }

    /**
     * Token-level twin of {@link #injectFields}: copies the client's
     * top-level object from {@code parser} to {@code generator} verbatim,
     * then appends the given fields — never materializing the document as a
     * tree. A client-supplied field of the same name is not removed (that
     * would need random access this never takes), but since the injected
     * fields are always written last, standard last-field-wins JSON parsing
     * (Jackson's included, which is what OpenSearch itself uses) resolves it
     * exactly like {@link #injectFields}'s overwrite: the injected value is
     * what survives.
     */
    public static void injectFieldsStreaming(
            com.fasterxml.jackson.core.JsonParser parser,
            com.fasterxml.jackson.core.JsonGenerator generator,
            Map<String, JsonNode> fields) throws java.io.IOException {
        if (parser.nextToken() != com.fasterxml.jackson.core.JsonToken.START_OBJECT) {
            throw new java.io.IOException("streaming ingest body must be a json object");
        }
        generator.writeStartObject();
        while (parser.nextToken() != com.fasterxml.jackson.core.JsonToken.END_OBJECT) {
            generator.writeFieldName(parser.currentName());
            parser.nextToken();
            copyValue(parser, generator);
        }
        for (Map.Entry<String, JsonNode> field : fields.entrySet()) {
            generator.writeFieldName(field.getKey());
            generator.writeTree(field.getValue());
        }
        generator.writeEndObject();
    }

    /**
     * Recursively copies the value the parser currently points at. Package-
     * visible so {@link Queries}'s streaming twin can reuse it for the
     * top-level fields it passes through untouched.
     */
    static void copyValue(
            com.fasterxml.jackson.core.JsonParser parser,
            com.fasterxml.jackson.core.JsonGenerator generator) throws java.io.IOException {
        switch (parser.currentToken()) {
            case START_OBJECT -> {
                generator.writeStartObject();
                while (parser.nextToken() != com.fasterxml.jackson.core.JsonToken.END_OBJECT) {
                    generator.writeFieldName(parser.currentName());
                    parser.nextToken();
                    copyValue(parser, generator);
                }
                generator.writeEndObject();
            }
            case START_ARRAY -> {
                generator.writeStartArray();
                while (parser.nextToken() != com.fasterxml.jackson.core.JsonToken.END_ARRAY) {
                    copyValue(parser, generator);
                }
                generator.writeEndArray();
            }
            default -> generator.copyCurrentEvent(parser);
        }
    }

    /** Removes each named field; returns how many were present. */
    public static int stripFields(ObjectNode doc, List<String> names) {
        int stripped = 0;
        for (String name : names) {
            if (doc.remove(name) != null) {
                stripped++;
            }
        }
        return stripped;
    }
}
