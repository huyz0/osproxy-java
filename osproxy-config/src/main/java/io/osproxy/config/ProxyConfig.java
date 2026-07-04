package io.osproxy.config;

import io.helidon.config.Config;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

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
 * @param maxBodyBytes request-body cap; anything larger is refused with 413
 *     before it is buffered (the transform must hold the whole body)
 * @param requireTlsForMutation refuse body-mutating requests over cleartext
 *     (NFR-S1 in the Rust project); only meaningful when TLS is configured
 * @param tls TLS ingress, when configured
 * @param cursorAffinityKey HMAC key sealing scroll/PIT cursor affinity;
 *     absent means the cursor endpoints are refused fail-closed
 */
public record ProxyConfig(
        int port,
        String upstream,
        String index,
        Map<String, String> tokens,
        long maxBodyBytes,
        boolean requireTlsForMutation,
        Optional<TlsSettings> tls,
        Optional<String> cursorAffinityKey) {

    /** PEM paths for the TLS listener; {@code clientCaPath} enables mTLS. */
    public record TlsSettings(String certPath, String keyPath, Optional<String> clientCaPath) {
        public TlsSettings {
            if (certPath == null || certPath.isEmpty() || keyPath == null || keyPath.isEmpty()) {
                throw new ConfigException("tls needs both cert-path and key-path");
            }
        }
    }

    /** The compatibility constructor: cleartext, 32 MiB cap, no TLS gate. */
    public ProxyConfig(int port, String upstream, String index, Map<String, String> tokens) {
        this(port, upstream, index, tokens,
                DEFAULT_MAX_BODY_BYTES, false, Optional.empty(), Optional.empty());
    }

    /** The pre-cursor form (tests): everything through tls, no cursor key. */
    public ProxyConfig(
            int port, String upstream, String index, Map<String, String> tokens,
            long maxBodyBytes, boolean requireTlsForMutation, Optional<TlsSettings> tls) {
        this(port, upstream, index, tokens,
                maxBodyBytes, requireTlsForMutation, tls, Optional.empty());
    }

    /** The default request-body cap (32 MiB), matching the Rust proxy. */
    public static final long DEFAULT_MAX_BODY_BYTES = 32L * 1024 * 1024;

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
        if (maxBodyBytes <= 0) {
            throw new ConfigException("max-body-bytes must be positive");
        }
        if (requireTlsForMutation && tls.isEmpty()) {
            throw new ConfigException(
                    "require-tls-for-mutation needs a tls listener (every mutation would 403)");
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
        Optional<TlsSettings> tls = root.get("tls.cert-path").asString().asOptional()
                .map(cert -> new TlsSettings(
                        cert,
                        root.get("tls.key-path").asString()
                                .orElseThrow(() -> new ConfigException(
                                        "osproxy.tls.key-path is required with cert-path")),
                        root.get("tls.client-ca-path").asString().asOptional()));
        return new ProxyConfig(
                root.get("port").asInt().orElse(9200),
                root.get("upstream").asString()
                        .orElseThrow(() -> new ConfigException("osproxy.upstream is required")),
                root.get("index").asString()
                        .orElseThrow(() -> new ConfigException("osproxy.index is required")),
                tokens,
                root.get("max-body-bytes").asLong().orElse(DEFAULT_MAX_BODY_BYTES),
                root.get("require-tls-for-mutation").asBoolean().orElse(false),
                tls,
                root.get("cursor-affinity-key").asString().asOptional());
    }
}
