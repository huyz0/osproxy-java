package io.osproxy.spi;

import io.osproxy.core.EndpointKind;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The read-only view of an authenticated, classified request handed to the
 * SPI and the pipeline. Headers are looked up case-insensitively; the body
 * is the raw bytes as received (the rewrite layer parses lazily).
 *
 * @param method the HTTP method
 * @param path the request path (no query string)
 * @param endpoint the classified endpoint kind
 * @param logicalIndex the client-visible index in the path, when present
 * @param docId the document id in the path, when present
 * @param headers parsed request headers in arrival order
 * @param body raw request body (empty array for bodyless requests)
 * @param principal the authenticated identity
 */
public record RequestCtx(
        HttpMethod method,
        String path,
        EndpointKind endpoint,
        Optional<String> logicalIndex,
        Optional<String> docId,
        List<Map.Entry<String, String>> headers,
        byte[] body,
        Principal principal) {

    public RequestCtx {
        if (method == null || path == null || endpoint == null || principal == null) {
            throw new IllegalArgumentException("request fields must be non-null");
        }
        headers = List.copyOf(headers);
        body = body == null ? new byte[0] : body;
    }

    /** Case-insensitive header lookup, first match wins. */
    public Optional<String> header(String name) {
        for (Map.Entry<String, String> h : headers) {
            if (h.getKey().equalsIgnoreCase(name)) {
                return Optional.of(h.getValue());
            }
        }
        return Optional.empty();
    }

    /** The HTTP method vocabulary the proxy accepts. */
    public enum HttpMethod {
        GET,
        PUT,
        POST,
        DELETE,
        HEAD
    }
}
