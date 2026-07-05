package io.osproxy.rewrite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@code _mget} and {@code _msearch} parsing. Both are demuxed per item by
 * the engine and their responses re-interleaved in request order.
 */
public final class Multi {

    private Multi() {}

    /**
     * One {@code _mget} doc reference. Like {@link Bulk.Item}, {@code raw}
     * is a mutable Jackson {@code ObjectNode} despite the {@code record}
     * framing — safe only because each item is parsed fresh per request,
     * never shared across requests or threads.
     */
    public record MgetItem(Optional<String> index, String id, ObjectNode raw) {}

    /** Parses an {@code _mget} body: {@code {"docs":[{"_index":..,"_id":..},…]}}. */
    public static List<MgetItem> parseMget(byte[] body) throws RewriteException {
        ObjectNode doc = Json.parseObject(body);
        JsonNode docs = doc.get("docs");
        if (!(docs instanceof ArrayNode array) || array.isEmpty()) {
            throw new RewriteException(
                    RewriteException.Kind.MALFORMED_MULTI, "_mget body needs a non-empty docs array");
        }
        List<MgetItem> items = new ArrayList<>(array.size());
        for (JsonNode item : array) {
            if (!(item instanceof ObjectNode obj) || !obj.has("_id") || !obj.get("_id").isTextual()) {
                throw new RewriteException(
                        RewriteException.Kind.MALFORMED_MULTI, "_mget doc needs a string _id");
            }
            Optional<String> index = obj.has("_index") && obj.get("_index").isTextual()
                    ? Optional.of(obj.get("_index").textValue())
                    : Optional.empty();
            items.add(new MgetItem(index, obj.get("_id").textValue(), obj));
        }
        return items;
    }

    /**
     * One {@code _msearch} pair: the header line (index selection) and the
     * search body line. {@code header}/{@code body} are mutable {@code
     * ObjectNode}s (see {@link MgetItem}'s note) — same reasoning, same
     * per-request-only safety.
     */
    public record MsearchItem(Optional<String> index, ObjectNode header, ObjectNode body) {}

    /** Parses an {@code _msearch} NDJSON payload of header/body line pairs. */
    public static List<MsearchItem> parseMsearch(byte[] body) throws RewriteException {
        String[] lines = new String(body, StandardCharsets.UTF_8).split("\n", -1);
        List<MsearchItem> items = new ArrayList<>();
        int i = 0;
        while (i < lines.length) {
            String headerLine = lines[i].strip();
            if (headerLine.isEmpty()) {
                i++;
                continue;
            }
            ObjectNode header = parseLine(headerLine, "_msearch header");
            i++;
            String bodyLine = i < lines.length ? lines[i].strip() : "";
            if (bodyLine.isEmpty()) {
                throw new RewriteException(
                        RewriteException.Kind.MALFORMED_MULTI,
                        "_msearch header missing its body line");
            }
            ObjectNode searchBody = parseLine(bodyLine, "_msearch body");
            Optional<String> index = header.has("index") && header.get("index").isTextual()
                    ? Optional.of(header.get("index").textValue())
                    : Optional.empty();
            items.add(new MsearchItem(index, header, searchBody));
            i++;
        }
        if (items.isEmpty()) {
            throw new RewriteException(
                    RewriteException.Kind.MALFORMED_MULTI, "_msearch body has no searches");
        }
        return items;
    }

    private static ObjectNode parseLine(String line, String what) throws RewriteException {
        JsonNode parsed;
        try {
            parsed = Json.MAPPER.readTree(line);
        } catch (java.io.IOException e) {
            throw new RewriteException(RewriteException.Kind.INVALID_JSON, "invalid " + what);
        }
        if (!(parsed instanceof ObjectNode obj)) {
            throw new RewriteException(
                    RewriteException.Kind.MALFORMED_MULTI, what + " must be a json object");
        }
        return obj;
    }
}
