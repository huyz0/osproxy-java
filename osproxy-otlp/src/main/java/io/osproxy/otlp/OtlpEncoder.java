package io.osproxy.otlp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.osproxy.observe.ExplainDoc;

/**
 * Pure OTLP/JSON encoding: one SERVER span per request under the service's
 * resource. Shape-only attributes ({@code osproxy.*}: endpoint, method,
 * status, error code) — no tenant values, matching the explain doc it is
 * built from.
 */
public final class OtlpEncoder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** OTLP SpanKind SERVER. */
    private static final int SPAN_KIND_SERVER = 2;

    private OtlpEncoder() {}

    /** Encodes one span as an OTLP {@code resourceSpans} export request. */
    public static byte[] encode(
            String serviceName, ExplainDoc doc, String spanId, long endUnixNanos) {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode resourceSpan = root.putArray("resourceSpans").addObject();
        ArrayNode resourceAttrs = resourceSpan.putObject("resource").putArray("attributes");
        attr(resourceAttrs, "service.name", serviceName);

        ObjectNode span = resourceSpan.putArray("scopeSpans").addObject()
                .putArray("spans").addObject();
        span.put("traceId", doc.traceId());
        span.put("spanId", spanId);
        span.put("name", doc.method() + " " + doc.endpoint().wireName());
        span.put("kind", SPAN_KIND_SERVER);
        span.put("startTimeUnixNano", String.valueOf(endUnixNanos - doc.durationNanos()));
        span.put("endTimeUnixNano", String.valueOf(endUnixNanos));

        ArrayNode attrs = span.putArray("attributes");
        attr(attrs, "osproxy.endpoint", doc.endpoint().wireName());
        attr(attrs, "osproxy.method", doc.method());
        attr(attrs, "osproxy.request_id", doc.requestId());
        intAttr(attrs, "http.response.status_code", doc.status());
        doc.errorCode().ifPresent(code -> attr(attrs, "osproxy.error", code));

        // OTLP status: ERROR for 5xx (our fault or upstream's), UNSET otherwise.
        span.putObject("status").put("code", doc.status() >= 500 ? 2 : 0);
        try {
            return MAPPER.writeValueAsBytes(root);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("in-memory encode cannot fail", e);
        }
    }

    private static void attr(ArrayNode attrs, String key, String value) {
        ObjectNode attr = attrs.addObject();
        attr.put("key", key);
        attr.putObject("value").put("stringValue", value);
    }

    private static void intAttr(ArrayNode attrs, String key, long value) {
        ObjectNode attr = attrs.addObject();
        attr.put("key", key);
        attr.putObject("value").put("intValue", String.valueOf(value));
    }
}
