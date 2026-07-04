package io.osproxy.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProxyConfigTest {

    private static Config config(Map<String, String> values) {
        return Config.just(ConfigSources.create(values));
    }

    @Test
    void loadsWithDefaultsAndTokens() {
        var cfg = ProxyConfig.load(config(Map.of(
                "osproxy.upstream", "http://localhost:9200",
                "osproxy.index", "shared",
                "osproxy.tokens.secret-a", "acme",
                "osproxy.tokens.secret-g", "globex")));
        assertThat(cfg.port()).isEqualTo(9200);
        assertThat(cfg.tokens()).containsEntry("secret-a", "acme").hasSize(2);
        assertThat(cfg.debugEndpoints()).isTrue();
    }

    @Test
    void debugEndpointsCanBeDisabled() {
        var cfg = ProxyConfig.load(config(Map.of(
                "osproxy.upstream", "http://localhost:9200",
                "osproxy.index", "shared",
                "osproxy.debug-endpoints", "false")));
        assertThat(cfg.debugEndpoints()).isFalse();
    }

    @Test
    void logDiagnosticCapturesDefaultsOffAndCanBeEnabled() {
        var defaults = ProxyConfig.load(config(Map.of(
                "osproxy.upstream", "http://localhost:9200",
                "osproxy.index", "shared")));
        assertThat(defaults.logDiagnosticCaptures()).isFalse();

        var enabled = ProxyConfig.load(config(Map.of(
                "osproxy.upstream", "http://localhost:9200",
                "osproxy.index", "shared",
                "osproxy.log-diagnostic-captures", "true")));
        assertThat(enabled.logDiagnosticCaptures()).isTrue();
    }

    @Test
    void missingRequiredKeysFailFastWithTheKeyName() {
        assertThatThrownBy(() -> ProxyConfig.load(config(Map.of(
                        "osproxy.index", "shared"))))
                .isInstanceOf(ProxyConfig.ConfigException.class)
                .hasMessageContaining("osproxy.upstream");
        assertThatThrownBy(() -> ProxyConfig.load(config(Map.of(
                        "osproxy.upstream", "http://x"))))
                .hasMessageContaining("osproxy.index");
    }

    @Test
    void validationRefusesBadValues() {
        assertThatThrownBy(() -> new ProxyConfig(70000, "http://x", "i", Map.of()))
                .hasMessageContaining("port");
        assertThatThrownBy(() -> new ProxyConfig(1, "ftp://x", "i", Map.of()))
                .hasMessageContaining("upstream");
        assertThatThrownBy(() -> new ProxyConfig(1, "http://x", "", Map.of()))
                .hasMessageContaining("index");
    }

    @Test
    void portOverrideIsHonored() {
        var cfg = ProxyConfig.load(config(Map.of(
                "osproxy.port", "0",
                "osproxy.upstream", "http://localhost:9200",
                "osproxy.index", "shared")));
        assertThat(cfg.port()).isZero();
        assertThat(cfg.tokens()).isEmpty();
    }

    @Test
    void tlsAndHardeningKeysLoad() {
        var cfg = ProxyConfig.load(config(Map.of(
                "osproxy.upstream", "http://localhost:9200",
                "osproxy.index", "shared",
                "osproxy.max-body-bytes", "1024",
                "osproxy.require-tls-for-mutation", "true",
                "osproxy.tls.cert-path", "/tmp/cert.pem",
                "osproxy.tls.key-path", "/tmp/key.pem",
                "osproxy.tls.client-ca-path", "/tmp/ca.pem")));
        assertThat(cfg.maxBodyBytes()).isEqualTo(1024);
        assertThat(cfg.requireTlsForMutation()).isTrue();
        assertThat(cfg.tls()).isPresent();
        assertThat(cfg.tls().get().clientCaPath()).contains("/tmp/ca.pem");
    }

    @Test
    void tlsCertWithoutKeyFailsFast() {
        assertThatThrownBy(() -> ProxyConfig.load(config(Map.of(
                        "osproxy.upstream", "http://x",
                        "osproxy.index", "i",
                        "osproxy.tls.cert-path", "/tmp/cert.pem"))))
                .isInstanceOf(ProxyConfig.ConfigException.class)
                .hasMessageContaining("key-path");
        assertThatThrownBy(() -> new ProxyConfig.TlsSettings("", "/k", java.util.Optional.empty()))
                .isInstanceOf(ProxyConfig.ConfigException.class);
    }

    @Test
    void badBodyCapFailsFast() {
        assertThatThrownBy(() -> new ProxyConfig(
                        0, "http://x", "i", Map.of(), 0, false, java.util.Optional.empty()))
                .hasMessageContaining("max-body-bytes");
    }

    @Test
    void cursorKeyAndLogRequestsLoad() {
        var cfg = ProxyConfig.load(config(Map.of(
                "osproxy.upstream", "http://localhost:9200",
                "osproxy.index", "shared",
                "osproxy.cursor-affinity-key", "0123456789abcdef",
                "osproxy.log-requests", "true")));
        assertThat(cfg.cursorAffinityKey()).contains("0123456789abcdef");
        assertThat(cfg.logRequests()).isTrue();
        // The compat constructors default the additive knobs off.
        var compat = new ProxyConfig(0, "http://x", "i", Map.of());
        assertThat(compat.cursorAffinityKey()).isEmpty();
        assertThat(compat.logRequests()).isFalse();
        var precursor = new ProxyConfig(
                0, "http://x", "i", Map.of(),
                ProxyConfig.DEFAULT_MAX_BODY_BYTES, false, java.util.Optional.empty(),
                java.util.Optional.of("0123456789abcdef"));
        assertThat(precursor.logRequests()).isFalse();
    }

    @Test
    void passthroughRequiresBothClusterAndEndpointOrNeither() {
        assertThat(new ProxyConfig(0, "http://x", "i", Map.of()).passthroughCluster()).isEmpty();

        assertThatThrownBy(() -> ProxyConfig.load(config(Map.of(
                        "osproxy.upstream", "http://localhost:9200",
                        "osproxy.index", "shared",
                        "osproxy.passthrough-cluster", "legacy"))))
                .isInstanceOf(ProxyConfig.ConfigException.class)
                .hasMessageContaining("passthrough");

        var cfg = ProxyConfig.load(config(Map.of(
                "osproxy.upstream", "http://localhost:9200",
                "osproxy.index", "shared",
                "osproxy.passthrough-cluster", "legacy",
                "osproxy.passthrough-endpoint", "http://legacy:9200",
                "osproxy.passthrough-indices", "legacy-, raw_")));
        assertThat(cfg.passthroughCluster()).contains("legacy");
        assertThat(cfg.passthroughEndpoint()).contains("http://legacy:9200");
        assertThat(cfg.passthroughIndices()).containsExactly("legacy-", "raw_");
    }

    @Test
    void headerForwardingDefaultsOnAndCanBeConfigured() {
        var defaults = ProxyConfig.load(config(Map.of(
                "osproxy.upstream", "http://localhost:9200",
                "osproxy.index", "shared")));
        assertThat(defaults.headerForwardingEnabled()).isTrue();
        assertThat(defaults.headerForwardingDeny()).isEmpty();

        var configured = ProxyConfig.load(config(Map.of(
                "osproxy.upstream", "http://localhost:9200",
                "osproxy.index", "shared",
                "osproxy.header-forwarding.enabled", "false",
                "osproxy.header-forwarding.deny", "authorization, x-secret")));
        assertThat(configured.headerForwardingEnabled()).isFalse();
        assertThat(configured.headerForwardingDeny()).containsExactly("authorization", "x-secret");
    }
}
