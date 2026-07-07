package io.osproxy.core;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * A single header the sink attaches to every upstream call for one cluster —
 * the proxy's own identity, distinct from whatever authenticated the client
 * to the proxy itself. A generic header rather than a closed Basic/Bearer/
 * ApiKey taxonomy: OpenSearch's security plugin (and any auth-aware proxy
 * sitting in front of it) is header-based regardless of scheme, so this
 * covers all of them, plus anything the operator's own setup expects,
 * without hardcoding assumptions about which scheme is in play.
 *
 * <p>Supplied per cluster by {@link io.osproxy.spi.TenancySpi#upstreamCredentials},
 * resolved fresh on every route (not cached), so a credential that rotates
 * (a refreshed access token, a short-lived STS-style secret) is naturally
 * supported without extra API surface — the implementer's own lookup does
 * the caching/refresh if it needs any. Takes precedence over a client's own
 * forwarded {@code Authorization} header (see {@code ForwardPolicy}) at the
 * sink's upstream choke point, since it is the proxy's deliberate identity
 * for that cluster, not passthrough.
 *
 * @param headerName the header to set (usually {@code Authorization})
 * @param headerValue the header's value, already in wire form (e.g. {@code
 *     "Basic <base64>"}, {@code "Bearer <token>"}, or a custom scheme)
 */
public record UpstreamCredentials(String headerName, String headerValue) {
    public UpstreamCredentials {
        if (headerName == null || headerName.isBlank()) {
            throw new IllegalArgumentException("headerName must not be blank");
        }
        if (headerValue == null || headerValue.isBlank()) {
            throw new IllegalArgumentException("headerValue must not be blank");
        }
    }

    /** {@code Authorization: Basic <base64(user:password)>}. */
    public static UpstreamCredentials basic(String username, String password) {
        String token = Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.UTF_8));
        return new UpstreamCredentials("Authorization", "Basic " + token);
    }

    /** {@code Authorization: Bearer <token>}. */
    public static UpstreamCredentials bearer(String token) {
        return new UpstreamCredentials("Authorization", "Bearer " + token);
    }
}
