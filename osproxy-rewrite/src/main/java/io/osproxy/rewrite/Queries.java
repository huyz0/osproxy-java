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
