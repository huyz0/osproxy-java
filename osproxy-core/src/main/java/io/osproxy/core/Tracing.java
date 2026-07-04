package io.osproxy.core;

/**
 * The per-request trace binding: the server binds the current
 * {@link TraceContext} as a {@link ScopedValue} around each request's
 * virtual thread, and the sink reads it at its upstream choke point to
 * inject {@code traceparent} — no header plumbing through every layer.
 */
public final class Tracing {

    /** Bound per request by the ingress; unbound outside a request. */
    public static final ScopedValue<TraceContext> CURRENT = ScopedValue.newInstance();

    private Tracing() {}
}
