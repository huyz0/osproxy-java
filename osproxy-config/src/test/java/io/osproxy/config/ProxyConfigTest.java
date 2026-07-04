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
}
