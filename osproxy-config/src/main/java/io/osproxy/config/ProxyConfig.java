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
 * @param logRequests emit one shape-only JSON line per request to stdout
 * @param directiveAdminToken bearer token for the directive admin endpoint;
 *     absent means the endpoint does not exist (fail closed)
 * @param fips refuse to boot unless a FIPS-validated JCE provider is engaged
 * @param otlpEndpoint OTLP collector base URL; absent means no export
 * @param serviceName the service name on exported spans
 * @param directivesUrl HTTP source polled for the fleet directive set;
 *     absent means directives are instance-local (the admin endpoint)
 * @param directivesPollSeconds the poll interval for {@code directivesUrl}
 */
public record ProxyConfig(
        int port,
        String upstream,
        String index,
        Map<String, String> tokens,
        long maxBodyBytes,
        boolean requireTlsForMutation,
        Optional<TlsSettings> tls,
        Optional<String> cursorAffinityKey,
        boolean logRequests,
        Optional<String> directiveAdminToken,
        boolean fips,
        Optional<String> otlpEndpoint,
        String serviceName,
        Optional<String> directivesUrl,
        int directivesPollSeconds) {

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
                maxBodyBytes, requireTlsForMutation, tls, Optional.empty(), false);
    }

    /** The pre-logging form (tests): everything through the cursor key. */
    public ProxyConfig(
            int port, String upstream, String index, Map<String, String> tokens,
            long maxBodyBytes, boolean requireTlsForMutation, Optional<TlsSettings> tls,
            Optional<String> cursorAffinityKey) {
        this(port, upstream, index, tokens,
                maxBodyBytes, requireTlsForMutation, tls, cursorAffinityKey, false,
                Optional.empty());
    }

    /** The pre-directives form (tests). */
    public ProxyConfig(
            int port, String upstream, String index, Map<String, String> tokens,
            long maxBodyBytes, boolean requireTlsForMutation, Optional<TlsSettings> tls,
            Optional<String> cursorAffinityKey, boolean logRequests) {
        this(port, upstream, index, tokens,
                maxBodyBytes, requireTlsForMutation, tls, cursorAffinityKey, logRequests,
                Optional.empty(), false);
    }

    /** The pre-fips form (tests). */
    public ProxyConfig(
            int port, String upstream, String index, Map<String, String> tokens,
            long maxBodyBytes, boolean requireTlsForMutation, Optional<TlsSettings> tls,
            Optional<String> cursorAffinityKey, boolean logRequests,
            Optional<String> directiveAdminToken) {
        this(port, upstream, index, tokens,
                maxBodyBytes, requireTlsForMutation, tls, cursorAffinityKey, logRequests,
                directiveAdminToken, false, Optional.empty(), "osproxy",
                Optional.empty(), 10);
    }

    /** The pre-otlp form (tests). */
    public ProxyConfig(
            int port, String upstream, String index, Map<String, String> tokens,
            long maxBodyBytes, boolean requireTlsForMutation, Optional<TlsSettings> tls,
            Optional<String> cursorAffinityKey, boolean logRequests,
            Optional<String> directiveAdminToken, boolean fips) {
        this(port, upstream, index, tokens,
                maxBodyBytes, requireTlsForMutation, tls, cursorAffinityKey, logRequests,
                directiveAdminToken, fips, Optional.empty(), "osproxy",
                Optional.empty(), 10);
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
                root.get("cursor-affinity-key").asString().asOptional(),
                root.get("log-requests").asBoolean().orElse(false),
                root.get("directive-admin-token").asString().asOptional(),
                root.get("fips").asBoolean().orElse(false),
                root.get("otlp-endpoint").asString().asOptional(),
                root.get("service-name").asString().orElse("osproxy"),
                root.get("directives-url").asString().asOptional(),
                root.get("directives-poll-seconds").asInt().orElse(10));
    }
}
