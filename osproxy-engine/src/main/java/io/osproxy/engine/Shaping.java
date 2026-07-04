package io.osproxy.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.osproxy.rewrite.DocIds;
import io.osproxy.rewrite.Fields;
import io.osproxy.rewrite.Json;
import io.osproxy.rewrite.RewriteException;
import io.osproxy.spi.DocIdRule;
import java.util.List;
import java.util.Optional;

/**
 * Response shaping: the read-path inverse of the write-path transforms.
 * Physical ids become logical, the physical index becomes the client's
 * logical index, and injected fields are stripped from every {@code _source}
 * — so what a client reads is exactly what it wrote.
 */
final class Shaping {

    private Shaping() {}

    /** The knobs one shaped response needs. */
    record View(
            String logicalIndex,
            String partition,
            Optional<DocIdRule> idRule,
            List<String> strippedFields) {}

    /** Shapes a get-by-id response document in place. */
    static void shapeGetDoc(ObjectNode doc, View view) throws RewriteException {
        relabel(doc, view);
        if (doc.get("_source") instanceof ObjectNode source) {
            Fields.stripFields(source, view.strippedFields());
        }
    }

    /** Shapes every hit of a search response in place. */
    static void shapeSearchHits(ObjectNode response, View view) throws RewriteException {
        if (!(response.at("/hits/hits") instanceof com.fasterxml.jackson.databind.node.ArrayNode hits)) {
            return;
        }
        for (JsonNode hit : hits) {
            if (hit instanceof ObjectNode obj) {
                shapeGetDoc(obj, view);
            }
        }
    }

    /** Rewrites {@code _index} to the logical name and {@code _id} to logical. */
    private static void relabel(ObjectNode doc, View view) throws RewriteException {
        if (doc.has("_index")) {
            doc.put("_index", view.logicalIndex());
        }
        JsonNode id = doc.get("_id");
        if (id != null && id.isTextual() && view.idRule().isPresent()) {
            DocIds.mapPhysicalToLogical(
                            view.idRule().get().template(), view.partition(), id.textValue())
                    .ifPresent(logical -> doc.put("_id", logical));
        }
    }

    /** Parses, shapes, and re-serializes an upstream response body. */
    static byte[] shape(byte[] upstream, View view, boolean search) throws RewriteException {
        ObjectNode doc = Json.parseObject(upstream);
        if (search) {
            shapeSearchHits(doc, view);
        } else {
            shapeGetDoc(doc, view);
        }
        return Json.writeBytes(doc);
    }
}
