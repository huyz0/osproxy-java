package io.osproxy.sink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.helidon.common.configurable.Resource;
import io.helidon.common.pki.Keys;
import io.helidon.common.tls.Tls;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.http.HttpRouting;
import io.osproxy.core.ClusterId;
import io.osproxy.core.Epoch;
import io.osproxy.core.IndexName;
import io.osproxy.core.Target;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Drives {@link OpenSearchSink} against a real TLS-terminating upstream
 * (a Helidon server on the self-signed {@code tls/server-cert.pem} the
 * ingress-hardening tests already use), through the sink's actual public
 * {@link Sink#write} API — the same TLS module either side of the proxy
 * uses, no internals-only unit test.
 */
class OpenSearchSinkHttpsTest {

    private static String resource(String name) {
        return Path.of("../osproxy-server/src/test/resources/tls", name)
                .toAbsolutePath().toString();
    }

    private WebServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop();
        }
    }

    private WebServer startHttpsUpstream() {
        Keys keys = Keys.builder()
                .pem(pem -> pem
                        .key(Resource.create(Path.of(resource("server-key.pem"))))
                        .certChain(Resource.create(Path.of(resource("server-cert.pem")))))
                .build();
        Tls tls = Tls.builder()
                .privateKey(keys.privateKey().orElseThrow())
                .privateKeyCertChain(keys.certChain())
                .build();
        return WebServer.builder()
                .tls(tls)
                .port(0)
                .routing((HttpRouting.Builder routing) -> routing.any(
                        (req, res) -> res.status(201).send("{\"result\":\"created\"}")))
                .build()
                .start();
    }

    /** A client config trusting the same self-signed cert as its CA. */
    private static Tls trustingConfig() {
        return Tls.builder()
                .trust(Keys.builder()
                        .pem(pem -> pem.certificates(
                                Resource.create(Path.of(resource("server-cert.pem")))))
                        .build()
                        .certs())
                .build();
    }

    @Test
    void aWriteOverHttpsCompletesARealTlsHandshakeAndRoundTrips() throws Exception {
        server = startHttpsUpstream();
        OpenSearchSink sink = new OpenSearchSink(
                Map.of(new ClusterId("c1"), "https://localhost:" + server.port()));
        sink.withUpstreamTls(trustingConfig());
        Target target = new Target(new ClusterId("c1"), new IndexName("orders"));

        var ack = sink.write(List.of(new WriteBatch.Op(
                target,
                new DocOp.Index(
                        "1", "{\"tenant_id\":\"acme\"}".getBytes(StandardCharsets.UTF_8),
                        Optional.empty()),
                new Epoch(1))));

        assertThat(ack.results()).hasSize(1);
        assertThat(ack.results().get(0).status()).isEqualTo(201);
    }

    @Test
    void aWriteOverHttpsWithoutUpstreamTlsConfiguredFailsClosed() throws Exception {
        server = startHttpsUpstream();
        OpenSearchSink sink = new OpenSearchSink(
                Map.of(new ClusterId("c1"), "https://localhost:" + server.port()));
        // No withUpstreamTls(...) call.
        Target target = new Target(new ClusterId("c1"), new IndexName("orders"));

        assertThatThrownBy(() -> sink.write(List.of(new WriteBatch.Op(
                        target,
                        new DocOp.Index(
                                "1", "{}".getBytes(StandardCharsets.UTF_8), Optional.empty()),
                        new Epoch(1)))))
                .isInstanceOf(SinkException.class)
                .hasMessageContaining("no upstream TLS configured");
    }
}
