package io.osproxy.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.helidon.webserver.WebServer;
import io.osproxy.config.ProxyConfig;
import io.osproxy.core.ClusterId;
import io.osproxy.core.IndexName;
import io.osproxy.engine.Pipeline;
import io.osproxy.sink.MemorySink;
import io.osproxy.tenancy.TenancyRouter;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.Test;

/** M4 ingress hardening: body cap, TLS listener, TLS-for-mutation gate. */
class IngressHardeningTest {

    private static Pipeline memoryPipeline() {
        MemorySink sink = new MemorySink();
        return new Pipeline(
                new TenancyRouter(new ReferenceTenancy(
                        new ClusterId("primary"), new IndexName("shared"))),
                sink, sink);
    }

    @Test
    void overCapBodiesAreRefusedWith413() throws Exception {
        WebServer server = WebServer.builder()
                .port(0)
                .routing(new AppHandler(
                        memoryPipeline(), new BearerAuth(Map.of()), 64, false)::route)
                .build()
                .start();
        try {
            var client = HttpClient.newHttpClient();
            var big = HttpRequest.newBuilder(
                            URI.create("http://localhost:" + server.port() + "/orders/_doc/1"))
                    .PUT(HttpRequest.BodyPublishers.ofString("{\"pad\":\"" + "x".repeat(100) + "\"}"))
                    .header("x-tenant", "acme")
                    .build();
            var resp = client.send(big, HttpResponse.BodyHandlers.ofString());
            assertThat(resp.statusCode()).isEqualTo(413);
            assertThat(resp.body()).isEqualTo("{\"error\":\"payload_too_large\"}");

            var small = HttpRequest.newBuilder(
                            URI.create("http://localhost:" + server.port() + "/orders/_doc/1"))
                    .PUT(HttpRequest.BodyPublishers.ofString("{}"))
                    .header("x-tenant", "acme")
                    .build();
            assertThat(client.send(small, HttpResponse.BodyHandlers.ofString()).statusCode())
                    .isEqualTo(201);
        } finally {
            server.stop();
        }
    }

    private static String resource(String name) {
        return Path.of("src/test/resources/tls", name).toAbsolutePath().toString();
    }

    /** A client that trusts anything — the server cert is self-signed. */
    private static HttpClient insecureClient() throws Exception {
        TrustManager[] trustAll = {new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {}

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }};
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(null, trustAll, new SecureRandom());
        return HttpClient.newBuilder().sslContext(ssl).build();
    }

    @Test
    void tlsListenerServesHttpsAndWritesRoundTrip() throws Exception {
        ProxyConfig cfg = new ProxyConfig(
                0, "http://localhost:59200", "shared", Map.of(),
                ProxyConfig.DEFAULT_MAX_BODY_BYTES, true,
                java.util.Optional.of(new ProxyConfig.TlsSettings(
                        resource("server-cert.pem"), resource("server-key.pem"),
                        java.util.Optional.empty())));
        // Wire the handler directly over a MemorySink (the upstream URL above
        // is never dialed), but with Main's TLS listener construction.
        WebServer server = WebServer.builder()
                .port(0)
                .routing(new AppHandler(
                        memoryPipeline(), new BearerAuth(Map.of()),
                        cfg.maxBodyBytes(), cfg.requireTlsForMutation())::route)
                .tls(Main.tls(cfg.tls().orElseThrow()))
                .build()
                .start();
        try {
            var client = insecureClient();
            var put = HttpRequest.newBuilder(
                            URI.create("https://localhost:" + server.port() + "/orders/_doc/1"))
                    .PUT(HttpRequest.BodyPublishers.ofString("{\"m\":1}"))
                    .header("x-tenant", "acme")
                    .build();
            var resp = client.send(put, HttpResponse.BodyHandlers.ofString());
            // Over TLS the mutation gate admits the write.
            assertThat(resp.statusCode()).isEqualTo(201);
        } finally {
            server.stop();
        }
    }

    @Test
    void cleartextMutationIsRefusedWhenTlsIsRequired() throws Exception {
        WebServer server = WebServer.builder()
                .port(0)
                .routing(new AppHandler(
                        memoryPipeline(), new BearerAuth(Map.of()),
                        ProxyConfig.DEFAULT_MAX_BODY_BYTES, true)::route)
                .build()
                .start();
        try {
            var client = HttpClient.newHttpClient();
            var put = HttpRequest.newBuilder(
                            URI.create("http://localhost:" + server.port() + "/orders/_doc/1"))
                    .PUT(HttpRequest.BodyPublishers.ofString("{}"))
                    .header("x-tenant", "acme")
                    .build();
            assertThat(client.send(put, HttpResponse.BodyHandlers.ofString()).statusCode())
                    .isEqualTo(403);
            // Reads still pass over cleartext.
            var get = HttpRequest.newBuilder(
                            URI.create("http://localhost:" + server.port() + "/orders/_doc/1"))
                    .GET()
                    .header("x-tenant", "acme")
                    .build();
            assertThat(client.send(get, HttpResponse.BodyHandlers.ofString()).statusCode())
                    .isEqualTo(404);
        } finally {
            server.stop();
        }
    }

    /** A client presenting the given identity, trusting anything. */
    private static HttpClient clientWithIdentity() throws Exception {
        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(pemBody(resource("client-cert.pem"))));
        PrivateKey key = KeyFactory.getInstance("EC")
                .generatePrivate(new PKCS8EncodedKeySpec(pemBody(resource("client-key.pem"))));
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry(
                "client", key, new char[0], new java.security.cert.Certificate[] {cert});
        KeyManagerFactory kmf =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, new char[0]);
        TrustManager[] trustAll = {new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {}

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }};
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(kmf.getKeyManagers(), trustAll, new SecureRandom());
        return HttpClient.newBuilder()
                .sslContext(ssl)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /** Strips PEM armor and base64-decodes the body (single-cert/key PEM files). */
    private static byte[] pemBody(String pemPath) throws Exception {
        String pem = Files.readString(Path.of(pemPath));
        String base64 = pem.lines()
                .filter(line -> !line.startsWith("-----"))
                .reduce("", String::concat);
        return Base64.getDecoder().decode(base64);
    }

    @Test
    void mutualTlsAcceptsAClientPresentingTheTrustedCertificate() throws Exception {
        ProxyConfig cfg = new ProxyConfig(
                0, "http://localhost:59200", "shared", Map.of(),
                ProxyConfig.DEFAULT_MAX_BODY_BYTES, false,
                java.util.Optional.of(new ProxyConfig.TlsSettings(
                        resource("server-cert.pem"), resource("server-key.pem"),
                        java.util.Optional.of(resource("ca-cert.pem")))));
        WebServer server = WebServer.builder()
                .port(0)
                .routing(new AppHandler(
                        memoryPipeline(), new BearerAuth(Map.of()),
                        cfg.maxBodyBytes(), cfg.requireTlsForMutation())::route)
                .tls(Main.tls(cfg.tls().orElseThrow()))
                .build()
                .start();
        try {
            var client = clientWithIdentity();
            var put = HttpRequest.newBuilder(
                            URI.create("https://localhost:" + server.port() + "/orders/_doc/1"))
                    .PUT(HttpRequest.BodyPublishers.ofString("{\"m\":1}"))
                    .header("x-tenant", "acme")
                    .build();
            var resp = client.send(put, HttpResponse.BodyHandlers.ofString());
            assertThat(resp.statusCode()).isEqualTo(201);
        } finally {
            server.stop();
        }
    }

    @Test
    void mutualTlsRefusesAClientWithNoCertificate() throws Exception {
        ProxyConfig cfg = new ProxyConfig(
                0, "http://localhost:59200", "shared", Map.of(),
                ProxyConfig.DEFAULT_MAX_BODY_BYTES, false,
                java.util.Optional.of(new ProxyConfig.TlsSettings(
                        resource("server-cert.pem"), resource("server-key.pem"),
                        java.util.Optional.of(resource("ca-cert.pem")))));
        WebServer server = WebServer.builder()
                .port(0)
                .routing(new AppHandler(
                        memoryPipeline(), new BearerAuth(Map.of()),
                        cfg.maxBodyBytes(), cfg.requireTlsForMutation())::route)
                .tls(Main.tls(cfg.tls().orElseThrow()))
                .build()
                .start();
        try {
            var client = insecureClient(); // trusts the server, presents no identity
            var put = HttpRequest.newBuilder(
                            URI.create("https://localhost:" + server.port() + "/orders/_doc/1"))
                    .PUT(HttpRequest.BodyPublishers.ofString("{\"m\":1}"))
                    .header("x-tenant", "acme")
                    .build();
            org.assertj.core.api.Assertions
                    .assertThatThrownBy(() -> client.send(put, HttpResponse.BodyHandlers.ofString()))
                    .isInstanceOf(java.io.IOException.class);
        } finally {
            server.stop();
        }
    }

    @Test
    void requireTlsWithoutAListenerIsAConfigError() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ProxyConfig(
                        0, "http://x", "i", Map.of(),
                        ProxyConfig.DEFAULT_MAX_BODY_BYTES, true, java.util.Optional.empty()))
                .isInstanceOf(ProxyConfig.ConfigException.class);
    }
}
