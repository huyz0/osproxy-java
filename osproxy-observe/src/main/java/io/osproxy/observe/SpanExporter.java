package io.osproxy.observe;

/**
 * The span-export seam: one SERVER span per completed request, shape-only.
 * Export is never on the critical path — implementations are fire-and-forget
 * and must swallow their own failures (telemetry never fails a request).
 */
public interface SpanExporter {

    /** Whether exporting is on (when off, callers skip all export cost). */
    boolean enabled();

    /**
     * Exports one request's span.
     *
     * @param doc the shape-only record (trace id, endpoint, status, duration)
     * @param spanId this hop's 16-hex span id
     * @param endUnixNanos wall-clock end time (start derives from duration)
     */
    void export(ExplainDoc doc, String spanId, long endUnixNanos);

    /** The default: exporting off, everything skipped. */
    SpanExporter NOOP = new SpanExporter() {
        @Override
        public boolean enabled() {
            return false;
        }

        @Override
        public void export(ExplainDoc doc, String spanId, long endUnixNanos) {}
    };
}
