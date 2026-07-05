package io.osproxy.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.helidon.webserver.WebServer;
import io.osproxy.core.ClusterId;
import io.osproxy.core.IndexName;
import io.osproxy.engine.Pipeline;
import io.osproxy.sink.OpenSearchSink;
import io.osproxy.tenancy.TenancyRouter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A tenanted {@code _search}/{@code _count} request streams its (wrapped)
 * query straight to the upstream via {@link AppHandler#streamSearch} →
 * {@link Pipeline#searchStreaming}, but — unlike passthrough/{@code _bulk}
 * — still enforces {@code maxBodyBytes}: search bodies are ordinary queries,
 * not the legitimately large aggregate payloads those two exist to escape.
 */
class SearchStreamingE2eTest {

    @Test
    void aTenantedSearchStreamsThroughIsolatedAndLabelClean() throws Exception {
        var cluster = new ClusterId("primary");
        var reference = new ReferenceTenancy(cluster, new IndexName("shared"));
        var upstreamBody = new java.util.concurrent.atomic.AtomicReference<String>();

        io.helidon.webserver.WebServer upstream = io.helidon.webserver.WebServer.builder()
                .routing((io.helidon.webserver.http.HttpRouting.Builder r) -> r.any((req, res) -> {
                    upstreamBody.set(req.content().hasEntity()
                            ? req.content().as(String.class) : "");
                    res.status(200)
                            .header(io.helidon.http.HeaderNames.CONTENT_TYPE, "application/json")
                            .send("{\"hits\":{\"hits\":[]}}");
                }))
                .port(0)
                .build()
                .start();
        try {
            var realSink = new OpenSearchSink(Map.of(cluster, "http://localhost:" + upstream.port()));
            Pipeline pipeline = new Pipeline(new TenancyRouter(reference), realSink, realSink);
            WebServer proxy = WebServer.builder()
                    .port(0)
                    .routing(new AppHandler(pipeline, new BearerAuth(Map.of()))::route)
                    .build()
                    .start();
            try {
                var client = HttpClient.newHttpClient();
                var req = HttpRequest.newBuilder(
                                URI.create("http://localhost:" + proxy.port() + "/shared/_search"))
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"query\":{\"match\":{\"msg\":\"hi\"}}}"))
                        .header("x-tenant", "acme")
                        .build();
                var resp = client.send(req, HttpResponse.BodyHandlers.ofString());

                assertThat(resp.statusCode()).isEqualTo(200);
                JsonNode wrapped = new ObjectMapper().readTree(upstreamBody.get());
                assertThat(wrapped.at("/query/bool/must/0/match/msg").textValue()).isEqualTo("hi");
                assertThat(wrapped.at("/query/bool/filter/0/term/_tenant").textValue())
                        .isEqualTo("acme");
            } finally {
                proxy.stop();
            }
        } finally {
            upstream.stop();
        }
    }

    @Test
    void aSearchBodyLargerThanTheCapIsStillRefusedWith413() throws Exception {
        var cluster = new ClusterId("primary");
        var reference = new ReferenceTenancy(cluster, new IndexName("shared"));
        var sink = new OpenSearchSink(Map.of(cluster, "http://localhost:1"));
        Pipeline pipeline = new Pipeline(new TenancyRouter(reference), sink, sink);
        long tinyCap = 64;
        WebServer proxy = WebServer.builder()
                .port(0)
                .routing(new AppHandler(pipeline, new BearerAuth(Map.of()), tinyCap, false)::route)
                .build()
                .start();
        try {
            var client = HttpClient.newHttpClient();
            String bigQuery = "{\"query\":{\"terms\":{\"id\":[" + "1,".repeat(100) + "1]}}}";
            var req = HttpRequest.newBuilder(
                            URI.create("http://localhost:" + proxy.port() + "/shared/_search"))
                    .POST(HttpRequest.BodyPublishers.ofString(bigQuery))
                    .header("x-tenant", "acme")
                    .build();
            var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            assertThat(resp.statusCode()).isEqualTo(413);
        } finally {
            proxy.stop();
        }
    }

    @Test
    void unfilterableSearchConstructsAreStillRefusedOnTheStreamingPath() throws Exception {
        var cluster = new ClusterId("primary");
        var reference = new ReferenceTenancy(cluster, new IndexName("shared"));
        var sink = new OpenSearchSink(Map.of(cluster, "http://localhost:1"));
        Pipeline pipeline = new Pipeline(new TenancyRouter(reference), sink, sink);
        WebServer proxy = WebServer.builder()
                .port(0)
                .routing(new AppHandler(pipeline, new BearerAuth(Map.of()))::route)
                .build()
                .start();
        try {
            var client = HttpClient.newHttpClient();
            var req = HttpRequest.newBuilder(
                            URI.create("http://localhost:" + proxy.port() + "/shared/_search"))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"suggest\":{\"s\":{\"text\":\"q\"}}}"))
                    .header("x-tenant", "acme")
                    .build();
            var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            assertThat(resp.statusCode()).isEqualTo(400);
        } finally {
            proxy.stop();
        }
    }
}
