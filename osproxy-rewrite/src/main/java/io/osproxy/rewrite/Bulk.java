package io.osproxy.rewrite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * NDJSON {@code _bulk} parsing: each action line ({@code index}, {@code
 * create}, {@code update}, {@code delete}) optionally followed by a document
 * line (all verbs except {@code delete}). The engine demuxes items by their
 * resolved partition and re-interleaves the upstream responses in request
 * order.
 */
public final class Bulk {

    private Bulk() {}

    /** A bulk verb. */
    public enum Action {
        INDEX,
        CREATE,
        UPDATE,
        DELETE;

        /** Whether this verb is followed by a document line. */
        public boolean hasDocLine() {
            return this != DELETE;
        }

        /** The NDJSON action key ({@code "index"}, …). */
        public String key() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * One parsed bulk operation.
     *
     * @param action the verb
     * @param index the per-action index override, when present
     * @param id the explicit document id, when present
     * @param doc the document line (update: the partial-update envelope), or
     *     empty for delete
     * @param actionMeta the parsed action metadata object (mutable copy, for
     *     id/index rewriting when re-emitting)
     */
    public record Item(
            Action action,
            Optional<String> index,
            Optional<String> id,
            Optional<ObjectNode> doc,
            ObjectNode actionMeta) {}

    /** Parses a full NDJSON payload into items, validating pairing. */
    public static List<Item> parseBulk(byte[] body) throws RewriteException {
        List<Item> items = new ArrayList<>();
        String text = new String(body, StandardCharsets.UTF_8);
        String[] lines = text.split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            String line = lines[i].strip();
            if (line.isEmpty()) {
                i++;
                continue;
            }
            ActionLine action = parseActionLine(line);
            Optional<ObjectNode> doc = Optional.empty();
            if (action.action.hasDocLine()) {
                i++;
                String docLine = i < lines.length ? lines[i].strip() : "";
                if (docLine.isEmpty()) {
                    throw new RewriteException(
                            RewriteException.Kind.MALFORMED_MULTI,
                            action.action.key() + " action missing its document line");
                }
                JsonNode parsed;
                try {
                    parsed = Json.MAPPER.readTree(docLine);
                } catch (java.io.IOException e) {
                    throw new RewriteException(
                            RewriteException.Kind.INVALID_JSON, "invalid bulk document line");
                }
                if (!(parsed instanceof ObjectNode obj)) {
                    throw new RewriteException(
                            RewriteException.Kind.NOT_AN_OBJECT,
                            "bulk document line must be an object");
                }
                doc = Optional.of(obj);
            }
            items.add(new Item(action.action, action.index, action.id, doc, action.meta));
            i++;
        }
        return items;
    }

    /**
     * Lazily parses NDJSON one item at a time from an open reader, for the
     * streaming bulk path — never materializes the whole payload as a
     * string or a list. A parse failure is reported as a {@link
     * RewriteException} wrapped in an unchecked {@link
     * java.io.UncheckedIOException}-style carrier, since {@link
     * java.util.Iterator} cannot declare checked exceptions; callers should
     * unwrap it (see the {@code getCause() instanceof RewriteException}
     * pattern in {@code MultiOps}).
     */
    public static java.util.Iterator<Item> parseBulkStream(java.io.BufferedReader reader) {
        return new java.util.Iterator<>() {
            private Item next;
            private boolean done;

            private void advance() {
                if (next != null || done) {
                    return;
                }
                try {
                    String line;
                    do {
                        line = reader.readLine();
                    } while (line != null && line.strip().isEmpty());
                    if (line == null) {
                        done = true;
                        return;
                    }
                    ActionLine action = parseActionLine(line.strip());
                    Optional<ObjectNode> doc = Optional.empty();
                    if (action.action.hasDocLine()) {
                        String docLine = reader.readLine();
                        if (docLine == null || docLine.strip().isEmpty()) {
                            throw new RewriteException(
                                    RewriteException.Kind.MALFORMED_MULTI,
                                    action.action.key() + " action missing its document line");
                        }
                        JsonNode parsed;
                        try {
                            parsed = Json.MAPPER.readTree(docLine.strip());
                        } catch (java.io.IOException e) {
                            throw new RewriteException(
                                    RewriteException.Kind.INVALID_JSON,
                                    "invalid bulk document line");
                        }
                        if (!(parsed instanceof ObjectNode obj)) {
                            throw new RewriteException(
                                    RewriteException.Kind.NOT_AN_OBJECT,
                                    "bulk document line must be an object");
                        }
                        doc = Optional.of(obj);
                    }
                    next = new Item(action.action, action.index, action.id, doc, action.meta);
                } catch (RewriteException e) {
                    throw new RuntimeException(e);
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }

            @Override
            public boolean hasNext() {
                advance();
                return next != null;
            }

            @Override
            public Item next() {
                advance();
                if (next == null) {
                    throw new java.util.NoSuchElementException();
                }
                Item item = next;
                next = null;
                return item;
            }
        };
    }

    private record ActionLine(Action action, Optional<String> index, Optional<String> id, ObjectNode meta) {}

    private static ActionLine parseActionLine(String line) throws RewriteException {
        JsonNode parsed;
        try {
            parsed = Json.MAPPER.readTree(line);
        } catch (java.io.IOException e) {
            throw new RewriteException(
                    RewriteException.Kind.INVALID_JSON, "invalid bulk action line");
        }
        if (!(parsed instanceof ObjectNode obj) || obj.size() != 1) {
            throw new RewriteException(
                    RewriteException.Kind.MALFORMED_MULTI,
                    "bulk action line must be a single-key object");
        }
        String key = obj.fieldNames().next();
        Action action;
        try {
            action = Action.valueOf(key.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new RewriteException(
                    RewriteException.Kind.MALFORMED_MULTI, "unknown bulk action: " + key);
        }
        JsonNode metaNode = obj.get(key);
        if (!(metaNode instanceof ObjectNode meta)) {
            throw new RewriteException(
                    RewriteException.Kind.MALFORMED_MULTI, "bulk action metadata must be an object");
        }
        return new ActionLine(
                action,
                textField(meta, "_index"),
                textField(meta, "_id"),
                meta);
    }

    private static Optional<String> textField(ObjectNode node, String name) {
        JsonNode v = node.get(name);
        return v != null && v.isTextual() ? Optional.of(v.textValue()) : Optional.empty();
    }
}
