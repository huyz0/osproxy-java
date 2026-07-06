package io.osproxy.otlp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.osproxy.observe.TenantMetrics;
import java.util.List;

/**
 * Pure OTLP/JSON encoding of a {@link TenantMetrics} snapshot: three
 * cumulative sum metrics (requests, failures, latency), one data point per
 * live tenant, {@code tenant} as the one attribute. Mirrors {@link
 * OtlpEncoder}'s shape-only-attributes stance, except here the tenant value
 * is the whole point (see {@code TenantMetrics}'s javadoc for why this is
 * the one place that is allowed).
 */
public final class OtlpMetricsEncoder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** OTLP AggregationTemporality CUMULATIVE. */
    private static final int TEMPORALITY_CUMULATIVE = 2;

    private OtlpMetricsEncoder() {}

    /** Encodes one snapshot as an OTLP {@code resourceMetrics} export request. */
    public static byte[] encode(
            String serviceName, List<TenantMetrics.TenantSnapshot> snapshot, long unixNanos) {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode resourceMetric = root.putArray("resourceMetrics").addObject();
        ArrayNode resourceAttrs = resourceMetric.putObject("resource").putArray("attributes");
        attr(resourceAttrs, "service.name", serviceName);

        ArrayNode metrics = resourceMetric.putArray("scopeMetrics").addObject().putArray("metrics");
        sumMetric(metrics, snapshot, unixNanos, "osproxy_tenant_requests_total",
                "Total requests seen for this tenant.", TenantMetrics.TenantSnapshot::requests);
        sumMetric(metrics, snapshot, unixNanos, "osproxy_tenant_failures_total",
                "Requests for this tenant that ended in a 4xx/5xx status.",
                TenantMetrics.TenantSnapshot::failures);
        sumMetric(metrics, snapshot, unixNanos, "osproxy_tenant_latency_nanos_total",
                "Cumulative wall-time spent serving this tenant's requests, in nanoseconds.",
                TenantMetrics.TenantSnapshot::durationNanos);

        try {
            return MAPPER.writeValueAsBytes(root);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("in-memory encode cannot fail", e);
        }
    }

    private static void sumMetric(
            ArrayNode metrics, List<TenantMetrics.TenantSnapshot> snapshot, long unixNanos,
            String name, String description,
            java.util.function.ToLongFunction<TenantMetrics.TenantSnapshot> value) {
        ObjectNode metric = metrics.addObject();
        metric.put("name", name);
        metric.put("description", description);
        ObjectNode sum = metric.putObject("sum");
        sum.put("aggregationTemporality", TEMPORALITY_CUMULATIVE);
        sum.put("isMonotonic", true);
        ArrayNode dataPoints = sum.putArray("dataPoints");
        for (var tenant : snapshot) {
            ObjectNode point = dataPoints.addObject();
            point.put("timeUnixNano", String.valueOf(unixNanos));
            point.put("asInt", String.valueOf(value.applyAsLong(tenant)));
            attr(point.putArray("attributes"), "tenant", tenant.tenant());
        }
    }

    private static void attr(ArrayNode attrs, String key, String value) {
        ObjectNode attr = attrs.addObject();
        attr.put("key", key);
        attr.putObject("value").put("stringValue", value);
    }
}
