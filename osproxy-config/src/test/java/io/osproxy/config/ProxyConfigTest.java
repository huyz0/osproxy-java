package io.osproxy.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import java.util.Map;
import java.util.Optional;
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
    void tenantMetricsEnabledDefaultsOffAndCanBeEnabled() {
        var defaults = ProxyConfig.load(config(Map.of(
                "osproxy.upstream", "http://localhost:9200",
                "osproxy.index", "shared")));
        assertThat(defaults.tenantMetricsEnabled()).isFalse();

        var enabled = ProxyConfig.load(config(Map.of(
                "osproxy.upstream", "http://localhost:9200",
                "osproxy.index", "shared",
                "osproxy.tenant-metrics-enabled", "true")));
        assertThat(enabled.tenantMetricsEnabled()).isTrue();

        var built = ProxyConfig.builder(9200, "http://localhost:9200", "shared")
                .tenantMetricsEnabled(true)
                .build();
        assertThat(built.tenantMetricsEnabled()).isTrue();
    }

    @Test
    void tenantMetricsExportIntervalDefaultsToFifteenSecondsAndCanBeOverridden() {
        var defaults = ProxyConfig.load(config(Map.of(
                "osproxy.upstream", "http://localhost:9200",
                "osproxy.index", "shared")));
        assertThat(defaults.tenantMetricsExportIntervalSeconds()).isEqualTo(15);

        var overridden = ProxyConfig.load(config(Map.of(
                "osproxy.upstream", "http://localhost:9200",
                "osproxy.index", "shared",
                "osproxy.tenant-metrics-export-interval-seconds", "5")));
        assertThat(overridden.tenantMetricsExportIntervalSeconds()).isEqualTo(5);

        assertThatThrownBy(() -> ProxyConfig.load(config(Map.of(
                "osproxy.upstream", "http://localhost:9200",
                "osproxy.index", "shared",
                "osproxy.tenant-metrics-export-interval-seconds", "0"))))
                .isInstanceOf(ProxyConfig.ConfigException.class);
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
    void upstreamTlsDefaultsToAbsent() {
        var cfg = ProxyConfig.load(config(Map.of(
                "osproxy.upstream", "http://localhost:9200",
                "osproxy.index", "shared")));
        assertThat(cfg.upstreamTls()).isEmpty();
    }

    @Test
    void upstreamTlsCaAloneEnablesServerAuthOnly() {
        var cfg = ProxyConfig.load(config(Map.of(
                "osproxy.upstream", "https://localhost:9200",
                "osproxy.index", "shared",
                "osproxy.upstream-tls.ca-path", "/tmp/upstream-ca.pem")));
        assertThat(cfg.upstreamTls()).isPresent();
        assertThat(cfg.upstreamTls().get().caPath()).isEqualTo("/tmp/upstream-ca.pem");
        assertThat(cfg.upstreamTls().get().certPath()).isEmpty();
    }

    @Test
    void upstreamTlsMutualIdentityLoads() {
        var cfg = ProxyConfig.load(config(Map.of(
                "osproxy.upstream", "https://localhost:9200",
                "osproxy.index", "shared",
                "osproxy.upstream-tls.ca-path", "/tmp/upstream-ca.pem",
                "osproxy.upstream-tls.cert-path", "/tmp/client-cert.pem",
                "osproxy.upstream-tls.key-path", "/tmp/client-key.pem")));
        assertThat(cfg.upstreamTls().get().certPath()).contains("/tmp/client-cert.pem");
        assertThat(cfg.upstreamTls().get().keyPath()).contains("/tmp/client-key.pem");
    }

    @Test
    void upstreamTlsCertRequiresItsKey() {
        assertThatThrownBy(() -> new ProxyConfig.UpstreamTlsSettings(
                        "/tmp/ca.pem", Optional.of("/tmp/cert.pem"), Optional.empty()))
                .isInstanceOf(ProxyConfig.ConfigException.class)
                .hasMessageContaining("cert-path and upstream-tls.key-path");
    }

    @Test
    void upstreamTlsNeedsACaPath() {
        assertThatThrownBy(() -> new ProxyConfig.UpstreamTlsSettings(
                        "", Optional.empty(), Optional.empty()))
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

    @Test
    void builderDefaultsMatchLoadDefaultsForAnAbsentKey() {
        var viaBuilder = ProxyConfig.builder(9200, "http://localhost:9200", "shared").build();
        var viaLoad = ProxyConfig.load(config(Map.of(
                "osproxy.upstream", "http://localhost:9200",
                "osproxy.index", "shared")));
        // Every field the builder defaults must match what load() defaults an
        // absent key to, so the two construction paths never silently diverge.
        assertThat(viaBuilder).isEqualTo(viaLoad);
    }

    @Test
    void builderSetsEveryFieldByName() {
        var tls = new ProxyConfig.TlsSettings("cert.pem", "key.pem", Optional.empty());
        var cfg = ProxyConfig.builder(8080, "http://localhost:9200", "shared")
                .tokens(Map.of("secret", "acme"))
                .maxBodyBytes(1024)
                .requireTlsForMutation(true)
                .tls(tls)
                .cursorAffinityKey("0123456789abcdef")
                .logRequests(true)
                .directiveAdminToken("admin-token")
                .fips(true)
                .otlpEndpoint("http://collector:4318")
                .serviceName("my-osproxy")
                .directivesUrl("http://directives")
                .directivesPollSeconds(5)
                .fanoutBootstrapServers("kafka:9092")
                .fanoutTopic("writes")
                .placementsUrl("http://placements")
                .placementsPollSeconds(7)
                .debugEndpoints(false)
                .logDiagnosticCaptures(true)
                .passthrough("legacy", "http://legacy:9200")
                .passthroughIndices(java.util.List.of("legacy-"))
                .headerForwardingEnabled(false)
                .headerForwardingDeny(java.util.List.of("authorization"))
                .build();

        assertThat(cfg.port()).isEqualTo(8080);
        assertThat(cfg.tokens()).containsEntry("secret", "acme");
        assertThat(cfg.maxBodyBytes()).isEqualTo(1024);
        assertThat(cfg.requireTlsForMutation()).isTrue();
        assertThat(cfg.tls()).contains(tls);
        assertThat(cfg.cursorAffinityKey()).contains("0123456789abcdef");
        assertThat(cfg.logRequests()).isTrue();
        assertThat(cfg.directiveAdminToken()).contains("admin-token");
        assertThat(cfg.fips()).isTrue();
        assertThat(cfg.otlpEndpoint()).contains("http://collector:4318");
        assertThat(cfg.serviceName()).isEqualTo("my-osproxy");
        assertThat(cfg.directivesUrl()).contains("http://directives");
        assertThat(cfg.directivesPollSeconds()).isEqualTo(5);
        assertThat(cfg.fanoutBootstrapServers()).contains("kafka:9092");
        assertThat(cfg.fanoutTopic()).isEqualTo("writes");
        assertThat(cfg.placementsUrl()).contains("http://placements");
        assertThat(cfg.placementsPollSeconds()).isEqualTo(7);
        assertThat(cfg.debugEndpoints()).isFalse();
        assertThat(cfg.logDiagnosticCaptures()).isTrue();
        assertThat(cfg.passthroughCluster()).contains("legacy");
        assertThat(cfg.passthroughEndpoint()).contains("http://legacy:9200");
        assertThat(cfg.passthroughIndices()).containsExactly("legacy-");
        assertThat(cfg.headerForwardingEnabled()).isFalse();
        assertThat(cfg.headerForwardingDeny()).containsExactly("authorization");
    }

    @Test
    void deleteByQueryExpansionDefaultsOffAndCanBeEnabled() {
        var defaults = ProxyConfig.load(config(Map.of(
                "osproxy.upstream", "http://localhost:9200",
                "osproxy.index", "shared")));
        assertThat(defaults.deleteByQueryExpansion()).isFalse();

        var enabled = ProxyConfig.load(config(Map.of(
                "osproxy.upstream", "http://localhost:9200",
                "osproxy.index", "shared",
                "osproxy.delete-by-query-expansion", "true")));
        assertThat(enabled.deleteByQueryExpansion()).isTrue();
    }

    @Test
    void adminPassThroughDefaultsAbsentAndCanBeConfigured() {
        var defaults = ProxyConfig.load(config(Map.of(
                "osproxy.upstream", "http://localhost:9200",
                "osproxy.index", "shared")));
        assertThat(defaults.adminCluster()).isEmpty();
        assertThat(defaults.adminAllowedPrefixes()).isEmpty();

        var configured = ProxyConfig.load(config(Map.of(
                "osproxy.upstream", "http://localhost:9200",
                "osproxy.index", "shared",
                "osproxy.admin-cluster", "ops",
                "osproxy.admin-endpoint", "http://ops:9200",
                "osproxy.admin-allowed-prefixes", "/_cat/, /_cluster/health")));
        assertThat(configured.adminCluster()).contains("ops");
        assertThat(configured.adminEndpoint()).contains("http://ops:9200");
        assertThat(configured.adminAllowedPrefixes())
                .containsExactly("/_cat/", "/_cluster/health");
    }

    @Test
    void builderSetsDeleteByQueryAndAdminFields() {
        var cfg = ProxyConfig.builder(9200, "http://localhost:9200", "shared")
                .deleteByQueryExpansion(true)
                .admin("ops", java.util.List.of("/_cat/"))
                .adminEndpoint("http://ops:9200")
                .build();
        assertThat(cfg.deleteByQueryExpansion()).isTrue();
        assertThat(cfg.adminCluster()).contains("ops");
        assertThat(cfg.adminAllowedPrefixes()).containsExactly("/_cat/");
        assertThat(cfg.adminEndpoint()).contains("http://ops:9200");
    }

    // Helidon's own environment-variable ConfigSource requires a literal
    // "_DASH_" token for every dash in a kebab-case key (e.g.
    // OSPROXY_TENANT_DASH_METRICS_DASH_ENABLED) rather than a plain
    // underscore — verified live against a real server boot where the
    // intuitive OSPROXY_TENANT_METRICS_ENABLED silently no-op'd. createConfig
    // exists so operators get the natural spelling instead; this guards
    // every multi-word key (including dotted-nested ones) end to end.
    @Test
    void createConfigMapsPlainUnderscoreEnvVarsOntoKebabCaseKeys() {
        var config = ProxyConfig.createConfig(Map.of(
                "OSPROXY_UPSTREAM", "http://es:9200",
                "OSPROXY_INDEX", "shared",
                "OSPROXY_TENANT_METRICS_ENABLED", "true",
                "OSPROXY_TENANT_METRICS_EXPORT_INTERVAL_SECONDS", "5",
                "OSPROXY_FANOUT_BOOTSTRAP_SERVERS", "broker:9092",
                "OSPROXY_HEADER_FORWARDING_ENABLED", "false",
                "OSPROXY_TLS_CERT_PATH", "/tmp/cert.pem",
                "OSPROXY_TLS_KEY_PATH", "/tmp/key.pem"));
        var cfg = ProxyConfig.load(config);
        assertThat(cfg.upstream()).isEqualTo("http://es:9200");
        assertThat(cfg.tenantMetricsEnabled()).isTrue();
        assertThat(cfg.tenantMetricsExportIntervalSeconds()).isEqualTo(5);
        assertThat(cfg.fanoutBootstrapServers()).contains("broker:9092");
        assertThat(cfg.headerForwardingEnabled()).isFalse();
        assertThat(cfg.tls()).isPresent();
        assertThat(cfg.tls().get().certPath()).isEqualTo("/tmp/cert.pem");
    }

    @Test
    void createConfigIgnoresUnrecognizedEnvVars() {
        var config = ProxyConfig.createConfig(Map.of(
                "OSPROXY_UPSTREAM", "http://es:9200",
                "OSPROXY_INDEX", "shared",
                "PATH", "/usr/bin",
                "SOME_UNRELATED_VAR", "x"));
        var cfg = ProxyConfig.load(config);
        assertThat(cfg.upstream()).isEqualTo("http://es:9200");
    }
}
