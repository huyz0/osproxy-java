package io.osproxy.sink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.helidon.common.configurable.Resource;
import io.helidon.common.pki.Keys;
import io.helidon.common.tls.Tls;
import io.helidon.common.tls.TlsClientAuth;
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
 * Drives {@link OpenSearchSink} against a mock upstream cluster that
 * <em>requires</em> a client certificate — the mutual-TLS counterpart to
 * {@link OpenSearchSinkHttpsTest}'s server-auth-only case. Reuses the same
 * {@code tls/} fixture files as the ingress-hardening tests: {@code
 * server-cert.pem} is self-signed (its own trust anchor); {@code
 * client-cert.pem} is a leaf issued by {@code ca-cert.pem}, so the mock
 * cluster trusts {@code ca-cert.pem}, not the leaf itself.
 */
class OpenSearchSinkMutualTlsTest {

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

    /** A mock cluster presenting the server identity, requiring the client one. */
    private WebServer startMutualTlsUpstream() {
        Keys identity = Keys.builder()
                .pem(pem -> pem
                        .key(Resource.create(Path.of(resource("server-key.pem"))))
                        .certChain(Resource.create(Path.of(resource("server-cert.pem")))))
                .build();
        Keys clientCa = Keys.builder()
                .pem(pem -> pem.certificates(
                        Resource.create(Path.of(resource("ca-cert.pem")))))
                .build();
        Tls tls = Tls.builder()
                .privateKey(identity.privateKey().orElseThrow())
                .privateKeyCertChain(identity.certChain())
                .trust(clientCa.certs())
                .clientAuth(TlsClientAuth.REQUIRED)
                // Endpoint identification (hostname verification) is a
                // client-side concept; the JDK's SSLEngine rejects it outright
                // when asked to apply it on the server role while validating
                // an incoming client certificate.
                .endpointIdentificationAlgorithm("NONE")
                .build();
        return WebServer.builder()
                .tls(tls)
                .port(0)
                .routing((HttpRouting.Builder routing) -> routing.any(
                        (req, res) -> res.status(201).send("{\"result\":\"created\"}")))
                .build()
                .start();
    }

    /** Trusts the server's own cert (its CA) and presents the client identity. */
    private static Tls clientTlsWithIdentity() {
        Keys serverCa = Keys.builder()
                .pem(pem -> pem.certificates(
                        Resource.create(Path.of(resource("server-cert.pem")))))
                .build();
        Keys identity = Keys.builder()
                .pem(pem -> pem
                        .key(Resource.create(Path.of(resource("client-key.pem"))))
                        .certChain(Resource.create(Path.of(resource("client-cert.pem")))))
                .build();
        return Tls.builder()
                .trust(serverCa.certs())
                .privateKey(identity.privateKey().orElseThrow())
                .privateKeyCertChain(identity.certChain())
                .build();
    }

    /** Trusts the server's cert but presents no client identity at all. */
    private static Tls clientTlsWithoutIdentity() {
        Keys serverCa = Keys.builder()
                .pem(pem -> pem.certificates(
                        Resource.create(Path.of(resource("server-cert.pem")))))
                .build();
        return Tls.builder().trust(serverCa.certs()).build();
    }

    @Test
    void aClientIdentityCompletesARealMutualTlsHandshakeAndWrites() throws Exception {
        server = startMutualTlsUpstream();
        OpenSearchSink sink = new OpenSearchSink(
                Map.of(new ClusterId("c1"), "https://localhost:" + server.port()));
        sink.withUpstreamTls(clientTlsWithIdentity());
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
    void noClientIdentityIsRefusedByAClusterThatRequiresMutualTls() throws Exception {
        server = startMutualTlsUpstream();
        OpenSearchSink sink = new OpenSearchSink(
                Map.of(new ClusterId("c1"), "https://localhost:" + server.port()));
        sink.withUpstreamTls(clientTlsWithoutIdentity());
        Target target = new Target(new ClusterId("c1"), new IndexName("orders"));

        assertThatThrownBy(() -> sink.write(List.of(new WriteBatch.Op(
                        target,
                        new DocOp.Index(
                                "1", "{}".getBytes(StandardCharsets.UTF_8), Optional.empty()),
                        new Epoch(1)))))
                .isInstanceOf(SinkException.class);
    }
}
