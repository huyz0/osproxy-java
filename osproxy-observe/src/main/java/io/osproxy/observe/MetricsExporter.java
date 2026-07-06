package io.osproxy.observe;

import java.util.List;

/**
 * The metrics-export seam: a periodic push of the current per-tenant
 * counters, mirroring {@link SpanExporter}'s shape for traces. Export is
 * never on the request's critical path — it runs off a poller, not per
 * request — and implementations must swallow their own failures the same
 * way (telemetry never fails a request, and here there is no request to
 * fail in the first place, but a broken collector still must not crash the
 * poller thread).
 */
public interface MetricsExporter {

    /** Whether exporting is on (when off, nothing is scheduled to call this). */
    boolean enabled();

    /**
     * Exports one snapshot of every currently-live tenant's counters.
     *
     * @param snapshot the tenant counters at this instant
     * @param unixNanos wall-clock time the snapshot was taken
     */
    void export(List<TenantMetrics.TenantSnapshot> snapshot, long unixNanos);

    /** The default: exporting off, nothing scheduled. */
    MetricsExporter NOOP = new MetricsExporter() {
        @Override
        public boolean enabled() {
            return false;
        }

        @Override
        public void export(List<TenantMetrics.TenantSnapshot> snapshot, long unixNanos) {}
    };
}
