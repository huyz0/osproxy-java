package io.osproxy.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.helidon.webserver.WebServer;
import io.osproxy.core.ClusterId;
import io.osproxy.core.IndexName;
import io.osproxy.engine.Pipeline;
import io.osproxy.sink.MemorySink;
import io.osproxy.tenancy.TenancyRouter;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The closed observe→act loop over HTTP: publish a directive, watch it gate
 * live recording without a restart, introspect it back.
 */
class AdminDirectivesLoopbackTest {

    @Test
    void publishGateIntrospectWithoutARestart() throws Exception {
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
                                1 << 20, false, observability)
                        .withAdminToken("admin-secret")::route)
                .build()
                .start();
        try {
            var client = HttpClient.newHttpClient();
            String base = "http://localhost:" + server.port();
            String admin = base + AppHandler.ADMIN_DIRECTIVES_PATH;

            // Unauthorized publishes are refused; wrong token too.
            assertThat(post(client, admin, null, "{}").statusCode()).isEqualTo(401);
            assertThat(post(client, admin, "wrong", "{}").statusCode()).isEqualTo(401);
            // A malformed publish never lands.
            assertThat(post(client, admin, "admin-secret",
                    "{\"directives\":[{\"tennant\":\"x\"}]}").statusCode()).isEqualTo(400);

            // Baseline VERBOSE (log wired): a request logs a line.
            put(client, base + "/orders/_doc/1");
            assertThat(logLines.toString()).contains("ingest-doc");
            logLines.reset();

            // Publish OFF for tenant acme: recording stops, live.
            assertThat(post(client, admin, "admin-secret", """
                    {"baseline":"verbose","directives":[
                      {"id":"silence-acme","level":"off","tenant":"acme",
                       "ttl_seconds":3600}]}
                    """).statusCode()).isEqualTo(200);
            put(client, base + "/orders/_doc/2");
            assertThat(logLines.toString()).isEmpty();

            // Introspection round-trips the published directive.
            var got = client.send(
                    HttpRequest.newBuilder(URI.create(admin))
                            .GET().header("authorization", "Bearer admin-secret").build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(got.statusCode()).isEqualTo(200);
            assertThat(got.body()).contains("\"id\":\"silence-acme\"")
                    .contains("\"tenant\":\"acme\"");
        } finally {
            server.stop();
        }
    }

    @Test
    void withoutAConfiguredTokenTheEndpointDoesNotExist() throws Exception {
        MemorySink sink = new MemorySink();
        Pipeline pipeline = new Pipeline(
                new TenancyRouter(new ReferenceTenancy(
                        new ClusterId("primary"), new IndexName("shared"))),
                sink, sink);
        WebServer server = WebServer.builder()
                .port(0)
                .routing(new AppHandler(pipeline, new BearerAuth(Map.of()))::route)
                .build()
                .start();
        try {
            var client = HttpClient.newHttpClient();
            String admin = "http://localhost:" + server.port()
                    + AppHandler.ADMIN_DIRECTIVES_PATH;
            assertThat(post(client, admin, "any", "{}").statusCode()).isEqualTo(400);
        } finally {
            server.stop();
        }
    }

    private static HttpResponse<String> post(
            HttpClient client, String uri, String token, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(uri))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("authorization", "Bearer " + token);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void put(HttpClient client, String uri) throws Exception {
        client.send(
                HttpRequest.newBuilder(URI.create(uri))
                        .PUT(HttpRequest.BodyPublishers.ofString("{}"))
                        .header("x-tenant", "acme")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
