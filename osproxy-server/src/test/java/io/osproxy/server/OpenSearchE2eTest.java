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
class OpenSearchE2eTest {

    private static GenericContainer<?> opensearch;
    private static WebServer proxy;
    private static HttpClient client;
    private static String proxyBase;
    private static String upstreamBase;

    @BeforeAll
    static void start() {
        opensearch = new GenericContainer<>("opensearchproject/opensearch:2.11.0")
                .withEnv("discovery.type", "single-node")
                .withEnv("plugins.security.disabled", "true")
                .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
                .withExposedPorts(9200)
                .waitingFor(Wait.forHttp("/").forPort(9200))
                .withStartupTimeout(Duration.ofMinutes(3));
        opensearch.start();
        upstreamBase = "http://" + opensearch.getHost() + ":" + opensearch.getMappedPort(9200);

        proxy = Main.start(new ProxyConfig(0, upstreamBase, "shared", Map.of(
                "secret-acme", "acme",
                "secret-globex", "globex")));
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
}
