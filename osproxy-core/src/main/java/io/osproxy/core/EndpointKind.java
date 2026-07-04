package io.osproxy.core;

/**
 * The request classes the proxy understands. Classification happens once at
 * ingress; everything downstream (resolution, rewrite, dispatch, response
 * shaping) switches on this kind, never re-parses the path.
 */
public enum EndpointKind {
    /** Single-document ingest: {@code PUT/POST /{index}/_doc[/{id}]}, {@code _create}. */
    INGEST_DOC("ingest-doc"),
    /** NDJSON bulk: {@code POST /_bulk} or {@code /{index}/_bulk}. */
    INGEST_BULK("ingest-bulk"),
    /** {@code GET/POST /{index}/_search}. */
    SEARCH("search"),
    /** {@code POST /_msearch}. */
    MULTI_SEARCH("multi-search"),
    /** {@code GET/POST /{index}/_count}. */
    COUNT("count"),
    /** {@code GET /{index}/_doc/{id}}. */
    GET_BY_ID("get-by-id"),
    /** {@code GET/POST /_mget}. */
    MULTI_GET("multi-get"),
    /** {@code DELETE /{index}/_doc/{id}}. */
    DELETE_BY_ID("delete-by-id"),
    /** Scroll/PIT lifecycle — classified, but out of this slice's scope. */
    CURSOR("cursor"),
    /** {@code _cat/_cluster/_nodes} operator surface — pass-through policy, later slice. */
    ADMIN("admin"),
    /** Anything unmatched: rejected fail-closed. */
    UNKNOWN("unknown");

    private final String wireName;

    EndpointKind(String wireName) {
        this.wireName = wireName;
    }

    /** Stable wire vocabulary (error bodies, logs); never the enum name. */
    public String wireName() {
        return wireName;
    }

    /** Whether this endpoint participates in tenancy resolution and rewrite. */
    public boolean isTenancyAware() {
        return switch (this) {
            case INGEST_DOC, INGEST_BULK, SEARCH, MULTI_SEARCH, COUNT,
                    GET_BY_ID, MULTI_GET, DELETE_BY_ID, CURSOR -> true;
            case ADMIN, UNKNOWN -> false;
        };
    }

    /** Whether this endpoint mutates data (and is therefore epoch-stamped). */
    public boolean isWrite() {
        return switch (this) {
            case INGEST_DOC, INGEST_BULK, DELETE_BY_ID -> true;
            default -> false;
        };
    }
}
