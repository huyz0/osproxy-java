package io.osproxy.config;

import io.helidon.config.Config;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The typed configuration of the reference server, loaded from Helidon
 * Config (application.yaml merged with {@code OSPROXY_*} environment
 * overrides) and validated fast-fail: a bad config never boots a proxy.
 *
 * @param port ingress port (0 = ephemeral, for tests)
 * @param upstream the one upstream cluster's base URL
 * @param index the shared physical index of the reference tenancy
 * @param tokens bearer token → tenant; empty means dev mode (the
 *     {@code x-tenant} header is trusted — never run that in production)
 */
public record ProxyConfig(int port, String upstream, String index, Map<String, String> tokens) {

    public ProxyConfig {
        if (port < 0 || port > 65535) {
            throw new ConfigException("port must be 0..65535");
        }
        if (upstream == null || !upstream.startsWith("http")) {
            throw new ConfigException("upstream must be an http(s) base url");
        }
        if (index == null || index.isEmpty()) {
            throw new ConfigException("index must be non-empty");
        }
        tokens = Map.copyOf(tokens);
    }

    /** A config that failed validation; the message says which key and why. */
    public static final class ConfigException extends RuntimeException {
        public ConfigException(String message) {
            super(message);
        }
    }

    /**
     * Loads from a Helidon Config tree rooted at {@code osproxy}. Tokens are
     * a nested map: {@code osproxy.tokens.<token>: <tenant>}.
     */
    public static ProxyConfig load(Config config) {
        Config root = config.get("osproxy");
        Map<String, String> tokens = new LinkedHashMap<>();
        root.get("tokens").detach().asMap().orElse(Map.of()).forEach(tokens::put);
        return new ProxyConfig(
                root.get("port").asInt().orElse(9200),
                root.get("upstream").asString()
                        .orElseThrow(() -> new ConfigException("osproxy.upstream is required")),
                root.get("index").asString()
                        .orElseThrow(() -> new ConfigException("osproxy.index is required")),
                tokens);
    }
}
