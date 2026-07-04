package io.osproxy.core;

/**
 * The stable wire vocabulary for failures. Every error a client can see maps
 * to one of these codes and one HTTP status — matching the Rust proxy's wire
 * contract so the two implementations are drop-in interchangeable.
 */
public enum ErrorCode {
    PARTITION_UNRESOLVED("partition_unresolved", 400),
    PLACEMENT_MISSING("placement_missing", 404),
    PLACEMENT_BACKEND_UNAVAILABLE("placement_backend_unavailable", 503),
    UNSUPPORTED_ENDPOINT("unsupported_endpoint", 400),
    STALE_EPOCH("stale_epoch", 409),
    AUTH_FAILED("auth_failed", 401),
    UNAUTHORIZED("unauthorized", 403),
    UPSTREAM_FAILED("upstream_failed", 502),
    OVERLOADED("overloaded", 429),
    PAYLOAD_TOO_LARGE("payload_too_large", 413),
    MALFORMED_REQUEST("malformed_request", 400),
    INTERNAL("internal", 500);

    private final String wireName;
    private final int httpStatus;

    ErrorCode(String wireName, int httpStatus) {
        this.wireName = wireName;
        this.httpStatus = httpStatus;
    }

    /** The snake_case code carried in error response bodies. */
    public String wireName() {
        return wireName;
    }

    /** The HTTP status this code is served with. */
    public int httpStatus() {
        return httpStatus;
    }

    /** The shape-only JSON error body: {@code {"error":"<code>"}}. No values leak. */
    public String toJsonBody() {
        return "{\"error\":\"" + wireName + "\"}";
    }
}
