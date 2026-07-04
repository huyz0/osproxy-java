package io.osproxy.sink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.helidon.webserver.WebServer;
import io.helidon.webserver.http.HttpRouting;
import io.osproxy.core.ClusterId;
import io.osproxy.core.Epoch;
import io.osproxy.core.IndexName;
import io.osproxy.core.Target;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Drives the real {@link OpenSearchSink} against a loopback Helidon server
 * that records what arrives — verifying the exact upstream wire calls
 * (method, path, routing, body) without a container.
 */
class OpenSearchSinkLoopbackTest {

    private record Seen(String method, String path, String body) {}

    private static final ConcurrentLinkedQueue<Seen> SEEN = new ConcurrentLinkedQueue<>();
    private static WebServer server;
    private static OpenSearchSink sink;
    private static Target target;

    @BeforeAll
    static void start() {
        server = WebServer.builder()
                .routing(OpenSearchSinkLoopbackTest::route)
                .port(0)
                .build()
                .start();
        sink = new OpenSearchSink(Map.of(
                new ClusterId("c1"), "http://localhost:" + server.port()));
        target = new Target(new ClusterId("c1"), new IndexName("orders"));
    }

    @AfterAll
    static void stop() {
        server.stop();
    }

    private static void route(HttpRouting.Builder routing) {
        routing.any((req, res) -> {
            String query = req.query().rawValue();
            SEEN.add(new Seen(
                    req.prologue().method().text(),
                    req.path().rawPath() + (query.isEmpty() ? "" : "?" + query),
                    req.content().hasEntity() ? req.content().as(String.class) : ""));
            res.status(200).send("{\"result\":\"ok\"}");
        });
    }

    @Test
    void writeOpsHitTheExpectedUpstreamPaths() throws Exception {
        SEEN.clear();
        sink.write(List.of(
                new WriteBatch.Op(target,
                        new DocOp.Index("acme:1", "{\"a\":1}".getBytes(StandardCharsets.UTF_8),
                                Optional.of("acme")),
                        Epoch.INITIAL),
                new WriteBatch.Op(target,
                        new DocOp.Create("acme:2", "{}".getBytes(StandardCharsets.UTF_8),
                                Optional.empty()),
                        Epoch.INITIAL),
                new WriteBatch.Op(target,
                        new DocOp.Update("acme:1", "{\"doc\":{}}".getBytes(StandardCharsets.UTF_8),
                                Optional.empty()),
                        Epoch.INITIAL),
                new WriteBatch.Op(target,
                        new DocOp.Delete("acme:1", Optional.of("acme")),
                        Epoch.INITIAL)));

        List<Seen> seen = List.copyOf(SEEN);
        assertThat(seen).extracting(Seen::method, Seen::path).containsExactly(
                org.assertj.core.groups.Tuple.tuple("PUT", "/orders/_doc/acme:1?routing=acme"),
                org.assertj.core.groups.Tuple.tuple("PUT", "/orders/_create/acme:2"),
                org.assertj.core.groups.Tuple.tuple("POST", "/orders/_update/acme:1"),
                org.assertj.core.groups.Tuple.tuple("DELETE", "/orders/_doc/acme:1?routing=acme"));
        assertThat(seen.get(0).body()).isEqualTo("{\"a\":1}");
    }

    @Test
    void readsHitGetSearchAndCount() throws Exception {
        SEEN.clear();
        sink.get(target, "acme:1", Optional.of("acme"));
        sink.search(target, "{\"query\":{\"match_all\":{}}}".getBytes(StandardCharsets.UTF_8));
        sink.count(target, new byte[0]);

        List<Seen> seen = List.copyOf(SEEN);
        assertThat(seen).extracting(Seen::method, Seen::path).containsExactly(
                org.assertj.core.groups.Tuple.tuple("GET", "/orders/_doc/acme:1?routing=acme"),
                org.assertj.core.groups.Tuple.tuple("POST", "/orders/_search"),
                org.assertj.core.groups.Tuple.tuple("POST", "/orders/_count"));
        assertThat(seen.get(2).body()).isEqualTo("{}");
    }

    @Test
    void unknownClusterFailsClosedAndConnectFailureIsUpstreamFailed() {
        var stranger = new Target(new ClusterId("nope"), new IndexName("orders"));
        assertThatThrownBy(() -> sink.get(stranger, "1", Optional.empty()))
                .isInstanceOf(SinkException.class)
                .extracting(e -> ((SinkException) e).errorCode())
                .isEqualTo(io.osproxy.core.ErrorCode.PLACEMENT_BACKEND_UNAVAILABLE);

        var unreachable = new OpenSearchSink(Map.of(
                new ClusterId("c1"), "http://localhost:1"));
        assertThatThrownBy(() -> unreachable.search(target, new byte[0]))
                .isInstanceOf(SinkException.class)
                .extracting(e -> ((SinkException) e).errorCode())
                .isEqualTo(io.osproxy.core.ErrorCode.UPSTREAM_FAILED);
    }

    @Test
    void endpointOverrideWinsOverConfiguredEndpoint() throws Exception {
        SEEN.clear();
        var overridden = new Target(
                new ClusterId("nope"), new IndexName("orders"),
                Optional.of("http://localhost:" + server.port()));
        assertThat(sink.get(overridden, "1", Optional.empty()).ok()).isTrue();
        assertThat(SEEN).hasSize(1);
    }
}
