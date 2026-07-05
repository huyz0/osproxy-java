package io.osproxy.rewrite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;

/**
 * Wrapping a client search body in the mandatory partition filter. The whole
 * client query becomes the {@code must} of a proxy-constructed {@code bool}
 * whose {@code filter} pins the partition fields — a structural enclosure the
 * client cannot override, the read-path counterpart of write-path injection.
 */
public final class Queries {

    private Queries() {}

    /**
     * Wraps {@code body}'s query so every match is additionally constrained
     * by {@code filterTerms} (field → term value). All other top-level keys
     * ({@code size}, {@code sort}, {@code aggs}, …) are preserved. With a
     * non-empty filter the body is screened for constructs that escape it
     * ({@code global} aggregations, {@code suggest}) and refused; an empty
     * filter (dedicated placement) wraps nothing and screens nothing.
     */
    public static byte[] wrapQuery(byte[] body, Map<String, JsonNode> filterTerms)
            throws RewriteException {
        ObjectNode doc = body.length == 0
                ? Json.MAPPER.createObjectNode()
                : Json.parseObject(body);
        if (filterTerms.isEmpty()) {
            return body.length == 0 ? Json.writeBytes(doc) : body;
        }
        screenUnfilterable(doc);

        JsonNode clientQuery = doc.remove("query");
        ObjectNode bool = Json.MAPPER.createObjectNode();
        ArrayNode must = bool.putArray("must");
        if (clientQuery != null) {
            must.add(clientQuery);
        } else {
            must.addObject().putObject("match_all");
        }
        ArrayNode filter = bool.putArray("filter");
        for (Map.Entry<String, JsonNode> term : filterTerms.entrySet()) {
            filter.addObject().putObject("term").set(term.getKey(), term.getValue());
        }
        doc.putObject("query").set("bool", bool);
        return Json.writeBytes(doc);
    }

    /**
     * Token-level twin of {@link #wrapQuery}, for a non-empty filter only
     * (an empty filter has nothing to stream-transform — the caller should
     * just forward the body verbatim, as the buffered path does). Copies
     * every top-level field except {@code query} straight through; {@code
     * query} is streamed into the {@code must} array of the constructed
     * {@code bool} rather than buffered, since wrapping it structurally
     * never needs to look inside it. {@code aggs}/{@code aggregations} is
     * the one field genuinely read as a tree (via {@link
     * com.fasterxml.jackson.core.JsonParser#readValueAsTree}) rather than
     * streamed, because the unfilterable check needs to inspect the whole
     * subtree before deciding whether to refuse — but that only buffers the
     * aggregations clause, never the (often much larger) query or hit-count
     * driving fields, so the win over full buffering still holds for the
     * common shape of a large body.
     */
    public static void wrapQueryStreaming(
            com.fasterxml.jackson.core.JsonParser parser,
            com.fasterxml.jackson.core.JsonGenerator generator,
            Map<String, JsonNode> filterTerms) throws java.io.IOException, RewriteException {
        com.fasterxml.jackson.core.JsonToken first = parser.nextToken();
        generator.writeStartObject();
        boolean sawQuery = false;
        if (first == com.fasterxml.jackson.core.JsonToken.START_OBJECT) {
            while (parser.nextToken() != com.fasterxml.jackson.core.JsonToken.END_OBJECT) {
                String name = parser.currentName();
                parser.nextToken();
                switch (name) {
                    case "suggest" -> throw new RewriteException(
                            RewriteException.Kind.UNFILTERABLE,
                            "suggest bypasses the partition filter");
                    case "query" -> {
                        sawQuery = true;
                        writeWrappedClientQuery(parser, generator, filterTerms);
                    }
                    case "aggs", "aggregations" -> {
                        JsonNode aggs = parser.readValueAsTree();
                        if (containsGlobalAgg(aggs)) {
                            throw new RewriteException(
                                    RewriteException.Kind.UNFILTERABLE,
                                    "global aggregation bypasses the partition filter");
                        }
                        generator.writeFieldName(name);
                        generator.writeTree(aggs);
                    }
                    default -> {
                        generator.writeFieldName(name);
                        Fields.copyValue(parser, generator);
                    }
                }
            }
        }
        if (!sawQuery) {
            writeSyntheticMatchAllQuery(generator, filterTerms);
        }
        generator.writeEndObject();
    }

    /** Writes {@code "query": {"bool": {"must": [<client query>], "filter": [...]}}}. */
    private static void writeWrappedClientQuery(
            com.fasterxml.jackson.core.JsonParser parser,
            com.fasterxml.jackson.core.JsonGenerator generator,
            Map<String, JsonNode> filterTerms) throws java.io.IOException {
        generator.writeFieldName("query");
        generator.writeStartObject();
        generator.writeFieldName("bool");
        generator.writeStartObject();
        generator.writeArrayFieldStart("must");
        Fields.copyValue(parser, generator);
        generator.writeEndArray();
        writeFilterArray(generator, filterTerms);
        generator.writeEndObject();
        generator.writeEndObject();
    }

    /** Writes {@code "query": {"bool": {"must": [{"match_all":{}}], "filter": [...]}}}. */
    private static void writeSyntheticMatchAllQuery(
            com.fasterxml.jackson.core.JsonGenerator generator, Map<String, JsonNode> filterTerms)
            throws java.io.IOException {
        generator.writeFieldName("query");
        generator.writeStartObject();
        generator.writeFieldName("bool");
        generator.writeStartObject();
        generator.writeArrayFieldStart("must");
        generator.writeStartObject();
        generator.writeObjectFieldStart("match_all");
        generator.writeEndObject();
        generator.writeEndObject();
        generator.writeEndArray();
        writeFilterArray(generator, filterTerms);
        generator.writeEndObject();
        generator.writeEndObject();
    }

    private static void writeFilterArray(
            com.fasterxml.jackson.core.JsonGenerator generator, Map<String, JsonNode> filterTerms)
            throws java.io.IOException {
        generator.writeArrayFieldStart("filter");
        for (Map.Entry<String, JsonNode> term : filterTerms.entrySet()) {
            generator.writeStartObject();
            generator.writeObjectFieldStart("term");
            generator.writeFieldName(term.getKey());
            generator.writeTree(term.getValue());
            generator.writeEndObject();
            generator.writeEndObject();
        }
        generator.writeEndArray();
    }

    /**
     * Refuses constructs whose results are computed outside the wrapped
     * query's scope: a {@code global} aggregation ignores the query entirely,
     * and {@code suggest} runs against the whole index.
     */
    private static void screenUnfilterable(ObjectNode doc) throws RewriteException {
        if (doc.has("suggest")) {
            throw new RewriteException(
                    RewriteException.Kind.UNFILTERABLE, "suggest bypasses the partition filter");
        }
        JsonNode aggs = doc.has("aggs") ? doc.get("aggs") : doc.get("aggregations");
        if (aggs != null && containsGlobalAgg(aggs)) {
            throw new RewriteException(
                    RewriteException.Kind.UNFILTERABLE,
                    "global aggregation bypasses the partition filter");
        }
    }

    /** Recursively looks for a {@code global} aggregation clause. */
    private static boolean containsGlobalAgg(JsonNode aggs) {
        if (aggs.isObject()) {
            if (aggs.has("global")) {
                return true;
            }
            for (JsonNode child : aggs) {
                if (containsGlobalAgg(child)) {
                    return true;
                }
            }
        }
        return false;
    }
}
