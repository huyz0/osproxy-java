package io.osproxy.otlp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.helidon.webserver.WebServer;
import io.osproxy.core.EndpointKind;
import io.osproxy.observe.ExplainDoc;
import io.osproxy.observe.TenantMetrics;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

class OtlpTest {

    private static final ExplainDoc DOC = new ExplainDoc(
            "req-1", "4bf92f3577b34da6a3ce929d0e0e4736", EndpointKind.SEARCH,
            "POST", 502, Optional.of("upstream_failed"), 1_500_000);

    @Test
    void theEncoderEmitsOneShapeOnlyServerSpan() throws Exception {
        byte[] encoded = OtlpEncoder.encode("osproxy", DOC, "00f067aa0ba902b7", 2_000_000_000L);
        JsonNode root = new ObjectMapper().readTree(encoded);
        JsonNode span = root.at("/resourceSpans/0/scopeSpans/0/spans/0");
        assertThat(root.at("/resourceSpans/0/resource/attributes/0/value/stringValue")
                .textValue()).isEqualTo("osproxy");
        assertThat(span.get("traceId").textValue())
                .isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(span.get("spanId").textValue()).isEqualTo("00f067aa0ba902b7");
        assertThat(span.get("kind").intValue()).isEqualTo(2);
        assertThat(span.get("name").textValue()).isEqualTo("POST search");
        // Start derives from end - duration; both are string nanos per OTLP JSON.
        assertThat(span.get("startTimeUnixNano").textValue()).isEqualTo("1998500000");
        assertThat(span.get("endTimeUnixNano").textValue()).isEqualTo("2000000000");
        // 5xx is span-status ERROR; the error code rides as an attribute.
        assertThat(span.at("/status/code").intValue()).isEqualTo(2);
        assertThat(span.get("attributes").toString())
                .contains("upstream_failed")
                .contains("http.response.status_code");

        // A 2xx doc: status UNSET, no error attribute.
        var ok = new ExplainDoc(
                "r", DOC.traceId(), EndpointKind.GET_BY_ID, "GET", 200,
                Optional.empty(), 1);
        JsonNode okSpan = new ObjectMapper()
                .readTree(OtlpEncoder.encode("s", ok, "00f067aa0ba902b7", 10))
                .at("/resourceSpans/0/scopeSpans/0/spans/0");
        assertThat(okSpan.at("/status/code").intValue()).isZero();
        assertThat(okSpan.get("attributes").toString()).doesNotContain("osproxy.error");
    }

    @Test
    void theHttpExporterPostsToV1TracesAndNeverThrows() {
        ConcurrentLinkedQueue<String> received = new ConcurrentLinkedQueue<>();
        WebServer collector = WebServer.builder()
                .port(0)
                .routing(r -> r.post("/v1/traces", (req, res) -> {
                    received.add(req.content().as(String.class));
                    res.send("{}");
                }))
                .build()
                .start();
        try {
            var exporter = new OtlpHttpExporter(
                    "http://localhost:" + collector.port(), "osproxy");
            assertThat(exporter.enabled()).isTrue();
            exporter.export(DOC, "00f067aa0ba902b7", 2_000_000_000L);
            Awaitility.await().atMost(5, TimeUnit.SECONDS)
                    .until(() -> !received.isEmpty());
            assertThat(received.peek()).contains("\"traceId\"");

            // A dead collector: export returns immediately, nothing thrown.
            var dead = new OtlpHttpExporter("http://localhost:1", "osproxy");
            dead.export(DOC, "00f067aa0ba902b7", 1);
        } finally {
            collector.stop();
        }
    }

    @Test
    void theMetricsEncoderEmitsCumulativeSumsPerTenant() throws Exception {
        var snapshot = List.of(
                new TenantMetrics.TenantSnapshot("acme", 3, 1, 6_000_000),
                new TenantMetrics.TenantSnapshot("globex", 1, 0, 500_000));
        byte[] encoded = OtlpMetricsEncoder.encode("osproxy", snapshot, 2_000_000_000L);
        JsonNode root = new ObjectMapper().readTree(encoded);
        assertThat(root.at("/resourceMetrics/0/resource/attributes/0/value/stringValue")
                .textValue()).isEqualTo("osproxy");
        JsonNode metrics = root.at("/resourceMetrics/0/scopeMetrics/0/metrics");
        assertThat(metrics).hasSize(3);
        JsonNode requests = metrics.get(0);
        assertThat(requests.get("name").textValue()).isEqualTo("osproxy_tenant_requests_total");
        assertThat(requests.at("/sum/aggregationTemporality").intValue()).isEqualTo(2);
        assertThat(requests.at("/sum/isMonotonic").booleanValue()).isTrue();
        JsonNode points = requests.at("/sum/dataPoints");
        assertThat(points).hasSize(2);
        assertThat(points.get(0).get("asInt").textValue()).isEqualTo("3");
        assertThat(points.get(0).at("/attributes/0/value/stringValue").textValue())
                .isEqualTo("acme");
        assertThat(points.get(0).get("timeUnixNano").textValue()).isEqualTo("2000000000");
    }

    @Test
    void theHttpMetricsExporterPostsToV1MetricsAndNeverThrows() {
        ConcurrentLinkedQueue<String> received = new ConcurrentLinkedQueue<>();
        WebServer collector = WebServer.builder()
                .port(0)
                .routing(r -> r.post("/v1/metrics", (req, res) -> {
                    received.add(req.content().as(String.class));
                    res.send("{}");
                }))
                .build()
                .start();
        try {
            var exporter = new OtlpHttpMetricsExporter(
                    "http://localhost:" + collector.port(), "osproxy");
            assertThat(exporter.enabled()).isTrue();
            exporter.export(
                    List.of(new TenantMetrics.TenantSnapshot("acme", 1, 0, 1)),
                    2_000_000_000L);
            Awaitility.await().atMost(5, TimeUnit.SECONDS)
                    .until(() -> !received.isEmpty());
            assertThat(received.peek()).contains("osproxy_tenant_requests_total");

            // A dead collector: export returns immediately, nothing thrown.
            var dead = new OtlpHttpMetricsExporter("http://localhost:1", "osproxy");
            dead.export(List.of(), 1);
        } finally {
            collector.stop();
        }
    }
}
