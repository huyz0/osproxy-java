package io.osproxy.sink;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.osproxy.core.Target;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An in-memory {@link Sink} + {@link Reader} for tests: stores documents per
 * cluster/index, answers OpenSearch-shaped responses, and evaluates the
 * query subset the proxy itself generates ({@code match_all}, {@code term},
 * {@code match}-as-equality, and {@code bool} with {@code must}/{@code
 * filter}) — enough to verify write↔read symmetry without a container.
 */
public final class MemorySink implements Sink, Reader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record DocKey(String cluster, String index, String id) {}

    private final Map<DocKey, ObjectNode> docs = new ConcurrentHashMap<>();

    private static DocKey key(Target target, String id) {
        return new DocKey(target.cluster().value(), target.index().value(), id);
    }

    @Override
    public WriteBatch.Ack write(List<WriteBatch.Op> ops) throws SinkException {
        List<WriteBatch.OpResult> results = new ArrayList<>(ops.size());
        for (WriteBatch.Op op : ops) {
            results.add(apply(op));
        }
        return new WriteBatch.Ack(results);
    }

    @Override
    public WriteBatch.OpResult writeStreaming(
            Target target, boolean create, String physicalId,
            java.io.InputStream requestBody, StreamTransform transform, Optional<String> routing)
            throws SinkException {
        // No real transport to stream over — run the transform into a
        // buffer and delegate to the ordinary path. Correct, not
        // streaming, but exercises the same transform a real sink would.
        byte[] doc;
        try {
            var out = new java.io.ByteArrayOutputStream();
            transform.apply(requestBody, out);
            doc = out.toByteArray();
        } catch (IOException e) {
            throw new SinkException(io.osproxy.core.ErrorCode.MALFORMED_REQUEST, "bad stream", e);
        }
        DocOp op = create
                ? new DocOp.Create(physicalId, doc, routing)
                : new DocOp.Index(physicalId, doc, routing);
        return apply(new WriteBatch.Op(target, op, io.osproxy.core.Epoch.INITIAL));
    }

    private WriteBatch.OpResult apply(WriteBatch.Op op) throws SinkException {
        DocKey key = key(op.target(), op.op().physicalId());
        switch (op.op()) {
            case DocOp.Index(String id, byte[] doc, var ignored) -> {
                boolean existed = docs.put(key, parse(doc)) != null;
                return new WriteBatch.OpResult(existed ? 200 : 201, existed ? "updated" : "created", id);
            }
            case DocOp.Create(String id, byte[] doc, var ignored) -> {
                if (docs.putIfAbsent(key, parse(doc)) != null) {
                    return new WriteBatch.OpResult(409, "version_conflict", id);
                }
                return new WriteBatch.OpResult(201, "created", id);
            }
            case DocOp.Update(String id, byte[] envelope, var ignored) -> {
                ObjectNode existing = docs.get(key);
                if (existing == null) {
                    return new WriteBatch.OpResult(404, "not_found", id);
                }
                JsonNode partial = parse(envelope).get("doc");
                if (partial instanceof ObjectNode obj) {
                    obj.properties().forEach(e -> existing.set(e.getKey(), e.getValue()));
                }
                return new WriteBatch.OpResult(200, "updated", id);
            }
            case DocOp.Delete(String id, var ignored) -> {
                boolean removed = docs.remove(key) != null;
                return new WriteBatch.OpResult(
                        removed ? 200 : 404, removed ? "deleted" : "not_found", id);
            }
        }
    }

    @Override
    public Response get(Target target, String physicalId, Optional<String> routing) {
        ObjectNode doc = docs.get(key(target, physicalId));
        ObjectNode out = MAPPER.createObjectNode();
        out.put("_index", target.index().value());
        out.put("_id", physicalId);
        out.put("found", doc != null);
        if (doc != null) {
            out.set("_source", doc.deepCopy());
        }
        return new Response(doc != null ? 200 : 404, out.toString().getBytes());
    }

    @Override
    public Response search(Target target, byte[] body) throws SinkException {
        List<Map.Entry<String, ObjectNode>> hits = evaluate(target, body);
        ObjectNode out = MAPPER.createObjectNode();
        ObjectNode hitsNode = out.putObject("hits");
        hitsNode.putObject("total").put("value", hits.size()).put("relation", "eq");
        ArrayNode array = hitsNode.putArray("hits");
        for (Map.Entry<String, ObjectNode> hit : hits) {
            ObjectNode h = array.addObject();
            h.put("_index", target.index().value());
            h.put("_id", hit.getKey());
            h.set("_source", hit.getValue().deepCopy());
        }
        return new Response(200, out.toString().getBytes());
    }

    @Override
    public Response count(Target target, byte[] body) throws SinkException {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("count", evaluate(target, body).size());
        return new Response(200, out.toString().getBytes());
    }

    @Override
    public Response searchStreaming(
            Target target, java.io.InputStream requestBody, StreamTransform transform)
            throws SinkException {
        return search(target, transformed(requestBody, transform));
    }

    @Override
    public Response countStreaming(
            Target target, java.io.InputStream requestBody, StreamTransform transform)
            throws SinkException {
        return count(target, transformed(requestBody, transform));
    }

    private static byte[] transformed(java.io.InputStream in, StreamTransform transform)
            throws SinkException {
        try {
            var out = new java.io.ByteArrayOutputStream();
            transform.apply(in, out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new SinkException(io.osproxy.core.ErrorCode.MALFORMED_REQUEST, "bad stream", e);
        }
    }

    private List<Map.Entry<String, ObjectNode>> evaluate(Target target, byte[] body)
            throws SinkException {
        JsonNode query = body.length == 0 ? null : parse(body).get("query");
        List<Map.Entry<String, ObjectNode>> hits = new ArrayList<>();
        // LinkedHashMap view for deterministic order in tests.
        Map<DocKey, ObjectNode> snapshot = new LinkedHashMap<>(docs);
        for (Map.Entry<DocKey, ObjectNode> entry : snapshot.entrySet()) {
            DocKey k = entry.getKey();
            if (!k.cluster().equals(target.cluster().value())
                    || !k.index().equals(target.index().value())) {
                continue;
            }
            if (query == null || matches(query, entry.getValue())) {
                hits.add(Map.entry(k.id(), entry.getValue()));
            }
        }
        return hits;
    }

    /** The query subset the proxy generates. Unknown clauses match nothing. */
    private static boolean matches(JsonNode query, ObjectNode doc) {
        if (query.has("match_all")) {
            return true;
        }
        if (query.has("term") || query.has("match")) {
            JsonNode clause = query.has("term") ? query.get("term") : query.get("match");
            var fields = clause.properties().iterator();
            if (!fields.hasNext()) {
                return false;
            }
            var field = fields.next();
            JsonNode actual = doc.get(field.getKey());
            JsonNode expected = field.getValue().isObject()
                    ? field.getValue().get("value")
                    : field.getValue();
            return actual != null && expected != null && actual.equals(expected);
        }
        if (query.has("bool")) {
            JsonNode bool = query.get("bool");
            for (String clause : List.of("must", "filter")) {
                JsonNode arr = bool.get(clause);
                if (arr == null) {
                    continue;
                }
                for (JsonNode sub : arr) {
                    if (!matches(sub, doc)) {
                        return false;
                    }
                }
            }
            return true;
        }
        return false;
    }

    // ---- cursor emulation: one batch carries everything, the next is empty ----

    private final java.util.concurrent.atomic.AtomicLong cursorSeq =
            new java.util.concurrent.atomic.AtomicLong();

    @Override
    public Response searchScroll(Target target, byte[] body, String scrollTtl)
            throws SinkException {
        Response first = search(target, body);
        ObjectNode doc = parse(first.body());
        doc.put("_scroll_id", "mem-scroll-" + cursorSeq.incrementAndGet());
        return new Response(first.status(), doc.toString().getBytes());
    }

    @Override
    public Response scrollNext(Target target, byte[] body) throws SinkException {
        ObjectNode envelope = parse(body);
        JsonNode id = envelope.get("scroll_id");
        if (id == null || !id.isTextual() || !id.textValue().startsWith("mem-scroll-")) {
            return new Response(404, "{\"error\":\"scroll not found\"}".getBytes());
        }
        ObjectNode out = MAPPER.createObjectNode();
        out.put("_scroll_id", id.textValue());
        ObjectNode hits = out.putObject("hits");
        hits.putObject("total").put("value", 0).put("relation", "eq");
        hits.putArray("hits");
        return new Response(200, out.toString().getBytes());
    }

    @Override
    public Response scrollDelete(Target target, byte[] body) throws SinkException {
        parse(body);
        return new Response(200, "{\"succeeded\":true}".getBytes());
    }

    @Override
    public Response pitOpen(Target target, String keepAlive) {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("pit_id", "mem-pit-" + cursorSeq.incrementAndGet());
        return new Response(200, out.toString().getBytes());
    }

    @Override
    public Response pitClose(Target target, byte[] body) throws SinkException {
        parse(body);
        return new Response(200, "{\"pits\":[]}".getBytes());
    }

    @Override
    public Response searchIndexless(Target target, byte[] body) throws SinkException {
        // A PIT search names no index; the memory emulation scans the target
        // index anyway (the PIT snapshot semantics are the real cluster's job)
        // and echoes the pit id back like OpenSearch does.
        ObjectNode query = parse(body);
        JsonNode pit = query.get("pit");
        Response result = search(target, body);
        if (pit != null && pit.has("id")) {
            ObjectNode doc = parse(result.body());
            doc.put("pit_id", pit.get("id").textValue());
            return new Response(result.status(), doc.toString().getBytes());
        }
        return result;
    }

    private static ObjectNode parse(byte[] doc) throws SinkException {
        try {
            JsonNode node = MAPPER.readTree(doc);
            if (node instanceof ObjectNode obj) {
                return obj;
            }
            throw new SinkException(
                    io.osproxy.core.ErrorCode.MALFORMED_REQUEST, "document must be an object");
        } catch (IOException e) {
            throw new SinkException(
                    io.osproxy.core.ErrorCode.MALFORMED_REQUEST, "invalid document json", e);
        }
    }
}
