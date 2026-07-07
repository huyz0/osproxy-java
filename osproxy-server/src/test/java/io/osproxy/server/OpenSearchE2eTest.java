package io.osproxy.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.helidon.webserver.WebServer;
import io.osproxy.config.ProxyConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * End-to-end against a real OpenSearch container: the same isolation
 * invariants the loopback test pins, but through a live cluster — including
 * that the physical documents really carry the prefixed ids and the tenant
 * marker (verified by reading the index directly, bypassing the proxy).
 *
 * <p>Tagged {@code integration}: excluded from the default {@code check},
 * opt in with {@code ./gradlew :osproxy-server:test -PincludeIntegration}.
 */
@Tag("integration")
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
class OpenSearchE2eTest {

    private static GenericContainer<?> opensearch;
    private static WebServer proxy;
    private static HttpClient client;
    private static String proxyBase;
    private static String upstreamBase;

    @BeforeAll
    static void start() {
        opensearch = new GenericContainer<>("opensearchproject/opensearch:3.7.0")
                .withEnv("discovery.type", "single-node")
                .withEnv("DISABLE_SECURITY_PLUGIN", "true")
                .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
                .withExposedPorts(9200)
                .waitingFor(Wait.forHttp("/").forPort(9200))
                .withStartupTimeout(Duration.ofMinutes(3));
        opensearch.start();
        upstreamBase = "http://" + opensearch.getHost() + ":" + opensearch.getMappedPort(9200);

        proxy = Main.start(new ProxyConfig(
                0, upstreamBase, "shared",
                Map.of("secret-acme", "acme", "secret-globex", "globex"),
                ProxyConfig.DEFAULT_MAX_BODY_BYTES, false, java.util.Optional.empty(),
                java.util.Optional.of("e2e-cursor-affinity-key")));
        proxyBase = "http://localhost:" + proxy.port();
        client = HttpClient.newHttpClient();
    }

    @AfterAll
    static void stop() {
        if (proxy != null) {
            proxy.stop();
        }
        if (opensearch != null) {
            opensearch.stop();
        }
    }

    private static HttpResponse<String> send(
            String base, String method, String path, String token, String body)
            throws Exception {
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
    @org.junit.jupiter.api.Order(1)
    void isolationHoldsAgainstARealCluster() throws Exception {
        // acme writes; the ack carries logical labels.
        var put = send(proxyBase, "PUT", "/orders/_doc/7", "secret-acme",
                "{\"msg\":\"hello\"}");
        assertThat(put.statusCode()).isEqualTo(201);
        assertThat(put.body()).contains("\"_id\":\"7\"");

        // acme reads it back clean; globex cannot see it.
        var got = send(proxyBase, "GET", "/orders/_doc/7", "secret-acme", null);
        assertThat(got.statusCode()).isEqualTo(200);
        assertThat(got.body()).contains("\"msg\":\"hello\"").doesNotContain("_tenant");
        assertThat(send(proxyBase, "GET", "/orders/_doc/7", "secret-globex", null)
                .statusCode()).isEqualTo(404);

        // The physical document (read straight off the cluster) really is
        // prefixed and marked — the isolation is in the data, not the proxy's
        // memory.
        var physical = send(upstreamBase, "GET", "/shared/_doc/acme:7?routing=acme", null, null);
        assertThat(physical.statusCode()).isEqualTo(200);
        assertThat(physical.body()).contains("\"_tenant\":\"acme\"");

        // Search is tenant-scoped after a refresh.
        send(upstreamBase, "POST", "/shared/_refresh", null, "");
        var acmeSearch = send(proxyBase, "POST", "/orders/_search", "secret-acme",
                "{\"query\":{\"match_all\":{}}}");
        assertThat(acmeSearch.body()).contains("\"_id\":\"7\"").doesNotContain("_tenant");
        var globexSearch = send(proxyBase, "POST", "/orders/_search", "secret-globex",
                "{\"query\":{\"match_all\":{}}}");
        assertThat(globexSearch.body()).doesNotContain("\"_id\":\"7\"");

        // Bulk + mget + count round-trip.
        var bulk = send(proxyBase, "POST", "/_bulk", "secret-acme", """
                {"index":{"_index":"orders","_id":"8"}}
                {"msg":"b"}
                {"delete":{"_index":"orders","_id":"7"}}
                """);
        assertThat(bulk.statusCode()).isEqualTo(200);
        assertThat(bulk.body()).contains("\"errors\":false");

        send(upstreamBase, "POST", "/shared/_refresh", null, "");
        var mget = send(proxyBase, "POST", "/_mget", "secret-acme",
                "{\"docs\":[{\"_index\":\"orders\",\"_id\":\"8\"},{\"_index\":\"orders\",\"_id\":\"7\"}]}");
        assertThat(mget.body()).contains("\"msg\":\"b\"");
        var count = send(proxyBase, "POST", "/orders/_count", "secret-acme", null);
        assertThat(count.body()).contains("\"count\":1");
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.Order(2)
    void scrollAndPitLifecycleAgainstARealCluster() throws Exception {
        send(proxyBase, "PUT", "/orders/_doc/s1", "secret-acme", "{\"m\":\"a\"}");
        send(proxyBase, "PUT", "/orders/_doc/s2", "secret-acme", "{\"m\":\"b\"}");
        send(upstreamBase, "POST", "/shared/_refresh", null, "");

        // Scroll: open with size=1, continue for the second hit, then close.
        var open = send(proxyBase, "POST", "/orders/_search?scroll=1m", "secret-acme",
                "{\"size\":1,\"query\":{\"match_all\":{}}}");
        assertThat(open.statusCode()).isEqualTo(200);
        var openDoc = new com.fasterxml.jackson.databind.ObjectMapper().readTree(open.body());
        String scrollId = openDoc.get("_scroll_id").textValue();
        // Sealed envelope, never the raw upstream id (which is base64-ish).
        assertThat(scrollId).contains(".");
        assertThat(openDoc.at("/hits/hits")).hasSize(1);
        assertThat(open.body()).doesNotContain("_tenant");

        var next = send(proxyBase, "POST", "/_search/scroll", "secret-acme",
                "{\"scroll\":\"1m\",\"scroll_id\":\"" + scrollId + "\"}");
        assertThat(next.statusCode()).isEqualTo(200);
        assertThat(next.body()).doesNotContain("_tenant");

        var closed = send(proxyBase, "DELETE", "/_search/scroll", "secret-acme",
                "{\"scroll_id\":\"" + scrollId + "\"}");
        assertThat(closed.statusCode()).isEqualTo(200);

        // A forged scroll id is refused, not forwarded.
        assertThat(send(proxyBase, "POST", "/_search/scroll", "secret-acme",
                "{\"scroll_id\":\"forged\"}").statusCode()).isEqualTo(400);

        // PIT: open, search through it (still tenant-scoped), close.
        var pitOpen = send(proxyBase, "POST",
                "/orders/_search/point_in_time?keep_alive=1m", "secret-acme", "");
        assertThat(pitOpen.statusCode()).isEqualTo(200);
        String pitId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(pitOpen.body()).get("pit_id").textValue();

        var pitSearch = send(proxyBase, "POST", "/_search", "secret-acme",
                "{\"pit\":{\"id\":\"" + pitId + "\"},\"query\":{\"match_all\":{}}}");
        assertThat(pitSearch.statusCode()).isEqualTo(200);
        assertThat(pitSearch.body()).doesNotContain("_tenant");

        var pitClose = send(proxyBase, "DELETE", "/_search/point_in_time", "secret-acme",
                "{\"pit_id\":[\"" + pitId + "\"]}");
        assertThat(pitClose.statusCode()).isEqualTo(200);
    }
}
