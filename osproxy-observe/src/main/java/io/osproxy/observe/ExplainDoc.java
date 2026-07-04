package io.osproxy.observe;

import io.osproxy.core.EndpointKind;
import java.util.Optional;

/**
 * The shape-only record of one request: what class it was, how it ended,
 * and how long it took — enough to diagnose a failure without ever carrying
 * a tenant value, a body, or a partition id (NFR-S2 in the Rust project).
 *
 * @param requestId the proxy-assigned id echoed to the client
 * @param traceId the W3C trace id the request ran under
 * @param endpoint the classified endpoint kind
 * @param method the HTTP method name
 * @param status the response status served
 * @param errorCode the wire error code for non-2xx shapes, when one applies
 * @param durationNanos wall time from ingress to response
 */
public record ExplainDoc(
        String requestId,
        String traceId,
        EndpointKind endpoint,
        String method,
        int status,
        Optional<String> errorCode,
        long durationNanos) {

    /** One stable JSON line (the log format and the explain wire format). */
    public String toJson() {
        return "{\"request_id\":\"" + requestId
                + "\",\"trace_id\":\"" + traceId
                + "\",\"endpoint\":\"" + endpoint.wireName()
                + "\",\"method\":\"" + method
                + "\",\"status\":" + status
                + errorCode.map(c -> ",\"error\":\"" + c + "\"").orElse("")
                + ",\"duration_nanos\":" + durationNanos + "}";
    }
}
