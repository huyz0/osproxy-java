package io.osproxy.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.osproxy.core.Clock;
import io.osproxy.core.EndpointKind;
import io.osproxy.observe.DiagLevel;
import io.osproxy.observe.Directive;
import io.osproxy.observe.DirectiveSet;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The directive publish/introspect wire format. The decoder is fail-closed:
 * an unknown key, a bad level, or a missing ttl refuses the whole publish —
 * a misspelled {@code tenant} must not silently become a fleet-wide match.
 * Introspection emits the same shape back (publish round-trips verbatim,
 * with {@code ttl_seconds} recomputed from the remaining time).
 */
public final class DirectivesApi {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> DIRECTIVE_KEYS = Set.of(
            "id", "level", "tenant", "index", "endpoint", "sample_per_mille", "ring_buffer",
            "ttl_seconds");

    private final Clock clock;

    public DirectivesApi(Clock clock) {
        this.clock = clock;
    }

    /** A publish that failed validation; the message names the offender. */
    public static final class InvalidDirectives extends Exception {
        InvalidDirectives(String message) {
            super(message);
        }
    }

    /**
     * Decodes a publish body:
     * {@code {"baseline":"shape","directives":[{...}]}}.
     */
    public DirectiveSet decode(byte[] body) throws InvalidDirectives {
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (IOException e) {
            throw new InvalidDirectives("body is not valid json");
        }
        if (!(root instanceof ObjectNode obj)) {
            throw new InvalidDirectives("body must be a json object");
        }
        for (String key : iterate(obj)) {
            if (!key.equals("baseline") && !key.equals("directives")) {
                throw new InvalidDirectives("unknown key: " + key);
            }
        }
        DiagLevel baseline = level(obj.path("baseline").asText("shape"));
        List<Directive> directives = new ArrayList<>();
        JsonNode list = obj.get("directives");
        if (list != null) {
            if (!(list instanceof ArrayNode array)) {
                throw new InvalidDirectives("directives must be an array");
            }
            for (JsonNode item : array) {
                directives.add(decodeDirective(item));
            }
        }
        return new DirectiveSet(baseline, directives);
    }

    private Directive decodeDirective(JsonNode item) throws InvalidDirectives {
        if (!(item instanceof ObjectNode obj)) {
            throw new InvalidDirectives("directive must be a json object");
        }
        for (String key : iterate(obj)) {
            if (!DIRECTIVE_KEYS.contains(key)) {
                throw new InvalidDirectives("unknown directive key: " + key);
            }
        }
        String id = obj.path("id").asText("");
        if (id.isEmpty()) {
            throw new InvalidDirectives("directive needs an id");
        }
        if (!obj.has("ttl_seconds") || !obj.get("ttl_seconds").canConvertToLong()) {
            throw new InvalidDirectives("directive needs a numeric ttl_seconds");
        }
        long ttlSeconds = obj.get("ttl_seconds").asLong();
        if (ttlSeconds <= 0) {
            throw new InvalidDirectives("ttl_seconds must be positive");
        }
        Optional<EndpointKind> endpoint = Optional.empty();
        if (obj.has("endpoint")) {
            endpoint = Optional.ofNullable(endpointByWireName(obj.get("endpoint").asText()));
            if (endpoint.isEmpty()) {
                throw new InvalidDirectives("unknown endpoint: " + obj.get("endpoint").asText());
            }
        }
        return new Directive(
                id,
                level(obj.path("level").asText("shape")),
                text(obj, "tenant"),
                text(obj, "index"),
                endpoint,
                obj.path("sample_per_mille").asInt(1000),
                obj.path("ring_buffer").asBoolean(false),
                clock.monotonicNanos() + ttlSeconds * 1_000_000_000L);
    }

    /** The active set as introspection JSON — republishable verbatim. */
    public String introspect(DirectiveSet set) {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("baseline", set.baseline().wireName());
        ArrayNode list = out.putArray("directives");
        long now = clock.monotonicNanos();
        for (Directive d : set.directives()) {
            ObjectNode item = list.addObject();
            item.put("id", d.id());
            item.put("level", d.level().wireName());
            d.tenant().ifPresent(t -> item.put("tenant", t));
            d.index().ifPresent(i -> item.put("index", i));
            d.endpoint().ifPresent(e -> item.put("endpoint", e.wireName()));
            item.put("sample_per_mille", d.samplePerMille());
            item.put("ring_buffer", d.ringBuffer());
            // Remaining ttl, never the host clock (no wall time leaks).
            item.put("ttl_seconds", Math.max(0, (d.expiresAtNanos() - now) / 1_000_000_000L));
        }
        return out.toString();
    }

    private static DiagLevel level(String name) throws InvalidDirectives {
        DiagLevel level = DiagLevel.fromWireName(name);
        if (level == null) {
            throw new InvalidDirectives("unknown level: " + name);
        }
        return level;
    }

    private static EndpointKind endpointByWireName(String name) {
        for (EndpointKind kind : EndpointKind.values()) {
            if (kind.wireName().equals(name)) {
                return kind;
            }
        }
        return null;
    }

    private static Optional<String> text(ObjectNode obj, String key) {
        return obj.has(key) ? Optional.of(obj.get(key).asText()) : Optional.empty();
    }

    private static Iterable<String> iterate(ObjectNode obj) {
        List<String> keys = new ArrayList<>();
        obj.fieldNames().forEachRemaining(keys::add);
        return keys;
    }
}
