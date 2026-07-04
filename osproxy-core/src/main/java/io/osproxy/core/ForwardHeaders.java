package io.osproxy.core;

import java.util.List;
import java.util.Map;

/**
 * The per-request client-header-forwarding binding: the ingress computes the
 * forwarded set (per its {@code ForwardPolicy}) from the raw client headers
 * and binds it as a {@link ScopedValue} around the request's virtual thread,
 * so the sink can attach it at its upstream choke point without threading a
 * parameter through every pipeline call site — the same pattern as
 * {@link Tracing}.
 */
public final class ForwardHeaders {

    /** Bound per request by the ingress; unbound (or empty) outside a request. */
    public static final ScopedValue<List<Map.Entry<String, String>>> CURRENT =
            ScopedValue.newInstance();

    /** The bound set, or empty when unbound (no forwarding configured/active). */
    public static List<Map.Entry<String, String>> currentOrEmpty() {
        return CURRENT.isBound() ? CURRENT.get() : List.of();
    }

    private ForwardHeaders() {}
}
