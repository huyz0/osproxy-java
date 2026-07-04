package io.osproxy.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.osproxy.config.ProxyConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Map;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.Test;

class CryptoPostureTest {

    @Test
    void theApprovedSetIsGcmOnlyModernTls() {
        assertThat(CryptoPosture.APPROVED_PROTOCOLS)
                .containsExactly("TLSv1.3", "TLSv1.2");
        for (String suite : CryptoPosture.APPROVED_SUITES) {
            assertThat(suite).contains("GCM");
            assertThat(suite).doesNotContain("CHACHA20").doesNotContain("CBC");
        }
    }

    @Test
    void fipsModeFailsLoudWithoutAValidatedProvider() {
        // This dev box runs stock providers: the gate must refuse, naming the fix.
        assertThat(CryptoPosture.installedFipsProvider()).isNull();
        assertThatThrownBy(CryptoPosture::requireFipsProvider)
                .isInstanceOf(CryptoPosture.FipsNotEngaged.class)
                .hasMessageContaining("bc-fips");
        assertThatThrownBy(() -> Main.start(new ProxyConfig(
                        0, "http://localhost:59200", "shared", Map.of(),
                        ProxyConfig.DEFAULT_MAX_BODY_BYTES, false,
                        java.util.Optional.empty(), java.util.Optional.empty(),
                        false, java.util.Optional.empty(), true)))
                .isInstanceOf(CryptoPosture.FipsNotEngaged.class);
    }

    @Test
    void theTlsListenerNegotiatesOnlyApprovedSuites() throws Exception {
        var cfg = new ProxyConfig.TlsSettings(
                resource("server-cert.pem"), resource("server-key.pem"),
                java.util.Optional.empty());
        var server = io.helidon.webserver.WebServer.builder()
                .port(0)
                .routing(r -> r.any((req, res) -> res.send("{}")))
                .tls(Main.tls(cfg))
                .build()
                .start();
        try {
            // A client offering only a non-approved suite is refused.
            HttpClient chacha = client("TLS_CHACHA20_POLY1305_SHA256");
            assertThatThrownBy(() -> chacha.send(
                            HttpRequest.newBuilder(URI.create(
                                            "https://localhost:" + server.port() + "/"))
                                    .GET().build(),
                            HttpResponse.BodyHandlers.ofString()))
                    .isInstanceOf(java.io.IOException.class);

            // An approved suite negotiates.
            HttpClient approved = client("TLS_AES_128_GCM_SHA256");
            assertThat(approved.send(
                            HttpRequest.newBuilder(URI.create(
                                            "https://localhost:" + server.port() + "/"))
                                    .GET().build(),
                            HttpResponse.BodyHandlers.ofString())
                    .statusCode()).isEqualTo(200);
        } finally {
            server.stop();
        }
    }

    private static String resource(String name) {
        return Path.of("src/test/resources/tls", name).toAbsolutePath().toString();
    }

    private static HttpClient client(String onlySuite) throws Exception {
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
        SSLParameters params = new SSLParameters();
        params.setCipherSuites(new String[] {onlySuite});
        return HttpClient.newBuilder().sslContext(ssl).sslParameters(params).build();
    }
}
