package io.osproxy.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.http.HttpRouting;
import io.osproxy.core.ClusterId;
import io.osproxy.core.IndexName;
import io.osproxy.engine.Pipeline;
import io.osproxy.tenancy.TenancyRouter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * A tenanted {@code _bulk} request, unlike passthrough, still resolves
 * tenancy and runs the per-item inject/construct-id transform — but nothing
 * about that transform needs the whole NDJSON body in memory at once, so
 * {@link AppHandler#streamBulk} parses and dispatches it one line at a time.
 * This proves it genuinely bypasses {@code maxBodyBytes} the same way
 * passthrough does, not just for the verbatim case.
 */
class BulkStreamingE2eTest {

    @Test
    void aTenantedBulkBodyLargerThanTheCapStreamsThroughAndDispatchesEveryItem() throws Exception {
        var totalUpstreamBytes = new AtomicLong();
        var upstreamRequests = new AtomicInteger();
        WebServer upstream = WebServer.builder()
                .routing((HttpRouting.Builder r) -> r.any((req, res) -> {
                    upstreamRequests.incrementAndGet();
                    if (req.content().hasEntity()) {
                        totalUpstreamBytes.addAndGet(req.content().as(byte[].class).length);
                    }
                    res.status(201).send("{\"result\":\"created\"}");
                }))
                .port(0)
                .build()
                .start();
        try {
            var cluster = new ClusterId("primary");
            var sink = new io.osproxy.sink.OpenSearchSink(
                    Map.of(cluster, "http://localhost:" + upstream.port()));
            var reference = new ReferenceTenancy(cluster, new IndexName("shared"));
            Pipeline pipeline = new Pipeline(new TenancyRouter(reference), sink, sink);
            // A tiny cap: the NDJSON body built below is far larger than this,
            // so the buffered (non-bulk-streaming) path would 413 it.
            long tinyCap = 1024;
            WebServer proxy = WebServer.builder()
                    .port(0)
                    .routing(new AppHandler(pipeline, new BearerAuth(Map.of()), tinyCap, false)
                            ::route)
                    .build()
                    .start();
            try {
                int docCount = 2000;
                StringBuilder ndjson = new StringBuilder();
                for (int i = 0; i < docCount; i++) {
                    ndjson.append("{\"index\":{\"_id\":\"").append(i).append("\"}}\n");
                    ndjson.append("{\"msg\":\"payload-").append(i).append("-padding-to-grow-the-body\"}\n");
                }
                byte[] body = ndjson.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                assertThat(body.length).isGreaterThan((int) tinyCap * 10);

                var client = HttpClient.newHttpClient();
                var req = HttpRequest.newBuilder(
                                URI.create("http://localhost:" + proxy.port() + "/orders/_bulk"))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .header("x-tenant", "acme")
                        .build();
                var resp = client.send(req, HttpResponse.BodyHandlers.ofString());

                assertThat(resp.statusCode()).isEqualTo(200);
                JsonNode json = new ObjectMapper().readTree(resp.body());
                assertThat(json.get("errors").booleanValue()).isFalse();
                assertThat(json.get("items")).hasSize(docCount);
                for (JsonNode item : json.get("items")) {
                    assertThat(item.at("/index/status").intValue()).isEqualTo(201);
                }
                assertThat(upstreamRequests.get()).isEqualTo(docCount);
                assertThat(totalUpstreamBytes.get()).isGreaterThan(tinyCap * 10);
            } finally {
                proxy.stop();
            }
        } finally {
            upstream.stop();
        }
    }

    @Test
    void anEmptyBulkBodyIsRefusedWithAProperErrorStatusNot200() throws Exception {
        var cluster = new ClusterId("primary");
        var sink = new io.osproxy.sink.MemorySink();
        var reference = new ReferenceTenancy(cluster, new IndexName("shared"));
        Pipeline pipeline = new Pipeline(new TenancyRouter(reference), sink, sink);
        WebServer proxy = WebServer.builder()
                .port(0)
                .routing(new AppHandler(pipeline, new BearerAuth(Map.of()))::route)
                .build()
                .start();
        try {
            var client = HttpClient.newHttpClient();
            var req = HttpRequest.newBuilder(URI.create("http://localhost:" + proxy.port() + "/_bulk"))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[0]))
                    .header("x-tenant", "acme")
                    .build();
            var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            assertThat(resp.statusCode()).isEqualTo(400);
        } finally {
            proxy.stop();
        }
    }
}
