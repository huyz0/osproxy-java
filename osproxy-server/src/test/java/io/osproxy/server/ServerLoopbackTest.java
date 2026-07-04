package io.osproxy.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.helidon.webserver.WebServer;
import io.osproxy.core.ClusterId;
import io.osproxy.core.IndexName;
import io.osproxy.engine.Pipeline;
import io.osproxy.sink.MemorySink;
import io.osproxy.tenancy.TenancyRouter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The full HTTP stack (auth → classify → pipeline) over a MemorySink,
 * driven by a real client against a real Helidon server on an ephemeral
 * port — everything but the upstream container.
 */
class ServerLoopbackTest {

    private static WebServer server;
    private static HttpClient client;
    private static String base;

    @BeforeAll
    static void start() {
        MemorySink sink = new MemorySink();
        Pipeline pipeline = new Pipeline(
                new TenancyRouter(new ReferenceTenancy(
                        new ClusterId("primary"), new IndexName("shared"))),
                sink, sink);
        AppHandler handler = new AppHandler(pipeline, new BearerAuth(Map.of(
                "secret-acme", "acme",
                "secret-globex", "globex")));
        server = WebServer.builder().port(0).routing(handler::route).build().start();
        client = HttpClient.newHttpClient();
        base = "http://localhost:" + server.port();
    }

    @AfterAll
    static void stop() {
        server.stop();
    }

    private static HttpResponse<String> send(
            String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body))
                .header("content-type", "application/json");
        if (token != null) {
            request.header("authorization", "Bearer " + token);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void authGateIs401WithoutAValidToken() throws Exception {
        assertThat(send("GET", "/orders/_doc/1", null, null).statusCode()).isEqualTo(401);
        assertThat(send("GET", "/orders/_doc/1", "wrong", null).statusCode()).isEqualTo(401);
    }

    @Test
    void ingestReadSearchDeleteRoundTripOverHttp() throws Exception {
        var put = send("PUT", "/orders/_doc/7", "secret-acme", "{\"msg\":\"hi\"}");
        assertThat(put.statusCode()).isEqualTo(201);
        assertThat(put.body()).contains("\"_id\":\"7\"").contains("\"_index\":\"orders\"");

        var got = send("GET", "/orders/_doc/7", "secret-acme", null);
        assertThat(got.statusCode()).isEqualTo(200);
        assertThat(got.body()).contains("\"msg\":\"hi\"").doesNotContain("_tenant");

        // The other tenant's token cannot see it.
        assertThat(send("GET", "/orders/_doc/7", "secret-globex", null).statusCode())
                .isEqualTo(404);

        var search = send("POST", "/orders/_search", "secret-acme",
                "{\"query\":{\"match_all\":{}}}");
        assertThat(search.body()).contains("\"_id\":\"7\"").doesNotContain("_tenant");
        assertThat(send("POST", "/orders/_search", "secret-globex",
                "{\"query\":{\"match_all\":{}}}").body()).doesNotContain("\"_id\":\"7\"");

        assertThat(send("DELETE", "/orders/_doc/7", "secret-acme", null).statusCode())
                .isEqualTo(200);
        assertThat(send("GET", "/orders/_doc/7", "secret-acme", null).statusCode())
                .isEqualTo(404);
    }

    @Test
    void unknownPathsAndMethodsAreRefusedShapeOnly() throws Exception {
        var unknown = send("GET", "/orders", "secret-acme", null);
        assertThat(unknown.statusCode()).isEqualTo(400);
        assertThat(unknown.body()).isEqualTo("{\"error\":\"unsupported_endpoint\"}");

        var patch = send("PATCH", "/orders/_doc/1", "secret-acme", "{}");
        assertThat(patch.statusCode()).isEqualTo(400);
    }

    @Test
    void devModeTrustsTheTenantHeader() throws Exception {
        MemorySink sink = new MemorySink();
        Pipeline pipeline = new Pipeline(
                new TenancyRouter(new ReferenceTenancy(
                        new ClusterId("primary"), new IndexName("shared"))),
                sink, sink);
        WebServer dev = WebServer.builder()
                .port(0)
                .routing(new AppHandler(pipeline, new BearerAuth(Map.of()))::route)
                .build()
                .start();
        try {
            var request = HttpRequest.newBuilder(
                            URI.create("http://localhost:" + dev.port() + "/orders/_doc/1"))
                    .PUT(HttpRequest.BodyPublishers.ofString("{}"))
                    .header("x-tenant", "acme")
                    .build();
            assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
                    .isEqualTo(201);
            // Without the header there is no principal: 401.
            var anon = HttpRequest.newBuilder(
                            URI.create("http://localhost:" + dev.port() + "/orders/_doc/2"))
                    .PUT(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();
            assertThat(client.send(anon, HttpResponse.BodyHandlers.ofString()).statusCode())
                    .isEqualTo(401);
        } finally {
            dev.stop();
        }
    }
}
