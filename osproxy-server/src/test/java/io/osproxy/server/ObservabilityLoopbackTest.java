package io.osproxy.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.helidon.webserver.WebServer;
import io.helidon.webserver.http.HttpRouting;
import io.osproxy.core.ClusterId;
import io.osproxy.core.IndexName;
import io.osproxy.core.Tracing;
import io.osproxy.engine.Pipeline;
import io.osproxy.sink.MemorySink;
import io.osproxy.sink.OpenSearchSink;
import io.osproxy.tenancy.TenancyRouter;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** M7 loopback: metrics, explain lookup, request log, trace propagation. */
class ObservabilityLoopbackTest {

    @Test
    void metricsExplainAndLogTellTheRequestsStory() throws Exception {
        var logLines = new ByteArrayOutputStream();
        var observability = new Observability(16, Optional.of(new PrintStream(logLines, true)));
        MemorySink sink = new MemorySink();
        Pipeline pipeline = new Pipeline(
                new TenancyRouter(new ReferenceTenancy(
                        new ClusterId("primary"), new IndexName("shared"))),
                sink, sink);
        WebServer server = WebServer.builder()
                .port(0)
                .routing(new AppHandler(
                        pipeline, new BearerAuth(Map.of()),
                        1 << 20, false, observability)::route)
                .build()
                .start();
        try {
            var client = HttpClient.newHttpClient();
            String base = "http://localhost:" + server.port();

            // One ok write, one unknown-path client error.
            var put = HttpRequest.newBuilder(URI.create(base + "/orders/_doc/1"))
                    .PUT(HttpRequest.BodyPublishers.ofString("{}"))
                    .header("x-tenant", "acme")
                    .build();
            var putResp = client.send(put, HttpResponse.BodyHandlers.ofString());
            assertThat(putResp.statusCode()).isEqualTo(201);
            String requestId = putResp.headers()
                    .firstValue(AppHandler.REQUEST_ID_HEADER).orElseThrow();

            var bad = HttpRequest.newBuilder(URI.create(base + "/nope"))
                    .GET().header("x-tenant", "acme").build();
            client.send(bad, HttpResponse.BodyHandlers.ofString());

            // Metrics (pre-auth, no token needed) tally both.
            var metrics = client.send(
                    HttpRequest.newBuilder(URI.create(base + AppHandler.METRICS_PATH))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(metrics.statusCode()).isEqualTo(200);
            assertThat(metrics.body())
                    .contains("\"requests_total\":2")
                    .contains("\"requests_ok\":1")
                    .contains("\"requests_client_error\":1");

            // Explain lookup by the echoed request id, shape-only.
            var explain = client.send(
                    HttpRequest.newBuilder(
                                    URI.create(base + AppHandler.EXPLAIN_PREFIX + requestId))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(explain.statusCode()).isEqualTo(200);
            assertThat(explain.body())
                    .contains("\"endpoint\":\"ingest-doc\"")
                    .contains("\"status\":201)".replace(")", ""))
                    .doesNotContain("acme");
            assertThat(client.send(
                            HttpRequest.newBuilder(
                                            URI.create(base + AppHandler.EXPLAIN_PREFIX + "ghost"))
                                    .GET().build(),
                            HttpResponse.BodyHandlers.ofString())
                    .statusCode()).isEqualTo(404);

            // The request log carries the same shape-only lines.
            String log = logLines.toString();
            assertThat(log).contains("\"endpoint\":\"ingest-doc\"");
            assertThat(log).contains("\"error\":\"unsupported_endpoint\"");
            assertThat(log).doesNotContain("acme");
        } finally {
            server.stop();
        }
    }

    @Test
    void tracesArePropagatedToTheUpstreamAndChained() throws Exception {
        // A loopback "upstream" recording the traceparent it receives.
        AtomicReference<String> seenTraceparent = new AtomicReference<>();
        WebServer upstream = WebServer.builder()
                .routing((HttpRouting.Builder r) -> r.any((req, res) -> {
                    seenTraceparent.set(req.headers()
                            .first(io.helidon.http.HeaderNames.create("traceparent"))
                            .orElse(null));
                    res.send("{}");
                }))
                .port(0)
                .build()
                .start();
        try {
            var sink = new OpenSearchSink(Map.of(
                    new ClusterId("primary"), "http://localhost:" + upstream.port()));
            Pipeline pipeline = new Pipeline(
                    new TenancyRouter(new ReferenceTenancy(
                            new ClusterId("primary"), new IndexName("shared"))),
                    sink, sink);
            WebServer proxy = WebServer.builder()
                    .port(0)
                    .routing(new AppHandler(pipeline, new BearerAuth(Map.of()))::route)
                    .build()
                    .start();
            try {
                var client = HttpClient.newHttpClient();
                String incoming = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
                var get = HttpRequest.newBuilder(URI.create(
                                "http://localhost:" + proxy.port() + "/orders/_search"))
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .header("x-tenant", "acme")
                        .header("traceparent", incoming)
                        .build();
                client.send(get, HttpResponse.BodyHandlers.ofString());

                String forwarded = seenTraceparent.get();
                assertThat(forwarded).isNotNull();
                // Same trace id, new span id: the proxy is a hop, not a relay.
                assertThat(forwarded).startsWith("00-4bf92f3577b34da6a3ce929d0e0e4736-");
                assertThat(forwarded).doesNotContain("00f067aa0ba902b7");
                assertThat(forwarded).endsWith("-01");
            } finally {
                proxy.stop();
            }
        } finally {
            upstream.stop();
        }
    }

    @Test
    void outsideARequestNoTraceIsBound() {
        assertThat(Tracing.CURRENT.isBound()).isFalse();
    }

    @Test
    void breakGlassCapturesOnlyWhenARingBufferDirectiveMatches() throws Exception {
        var observability = new Observability(16, Optional.empty());
        MemorySink sink = new MemorySink();
        Pipeline pipeline = new Pipeline(
                new TenancyRouter(new ReferenceTenancy(
                        new ClusterId("primary"), new IndexName("shared"))),
                sink, sink);
        WebServer server = WebServer.builder()
                .port(0)
                .routing(new AppHandler(
                                pipeline, new BearerAuth(Map.of()),
                                1 << 20, false, observability)
                        .withAdminToken("secret")::route)
                .build()
                .start();
        try {
            var client = HttpClient.newHttpClient();
            String base = "http://localhost:" + server.port();

            // Before any directive: the tape stays empty even after a request.
            client.send(HttpRequest.newBuilder(URI.create(base + "/orders/_doc/1"))
                            .PUT(HttpRequest.BodyPublishers.ofString("{}"))
                            .header("x-tenant", "acme").build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(fetchBreakGlass(client, base)).isEqualTo("[]");

            // Publish a ring_buffer directive targeting this tenant.
            String publish = """
                    {"directives":[
                      {"id":"forensic-acme","level":"shape","tenant":"acme",
                       "ring_buffer":true,"ttl_seconds":60}
                    ]}
                    """;
            var publishResp = client.send(
                    HttpRequest.newBuilder(URI.create(base + AppHandler.ADMIN_DIRECTIVES_PATH))
                            .POST(HttpRequest.BodyPublishers.ofString(publish))
                            .header("authorization", "Bearer secret")
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(publishResp.statusCode()).isEqualTo(200);

            client.send(HttpRequest.newBuilder(URI.create(base + "/orders/_doc/2"))
                            .PUT(HttpRequest.BodyPublishers.ofString("{}"))
                            .header("x-tenant", "acme").build(),
                    HttpResponse.BodyHandlers.ofString());

            String tape = fetchBreakGlass(client, base);
            assertThat(tape).contains("\"endpoint\":\"ingest-doc\"");
        } finally {
            server.stop();
        }
    }

    private static String fetchBreakGlass(HttpClient client, String base) throws Exception {
        var resp = client.send(
                HttpRequest.newBuilder(URI.create(base + AppHandler.BREAKGLASS_PATH))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        return resp.body();
    }
}
