package io.osproxy.server;

import io.osproxy.observe.MetricsExporter;
import io.osproxy.observe.TenantMetrics;

/**
 * Periodically pushes a {@link TenantMetrics} snapshot to a {@link
 * MetricsExporter}, on one virtual thread. Unlike the per-request span
 * export (fired inline on the request's own thread), tenant counters are
 * cumulative and cheap to snapshot, so a poll loop is the natural shape —
 * mirrors {@code PollingDirectiveStore}'s sleep-loop rather than
 * introducing a new scheduling primitive for one caller.
 */
public final class TenantMetricsExportScheduler implements AutoCloseable {

    private final Thread poller;
    private volatile boolean running = true;

    public TenantMetricsExportScheduler(
            TenantMetrics metrics, MetricsExporter exporter, long intervalMillis) {
        this.poller = Thread.ofVirtual()
                .name("osproxy-tenant-metrics-exporter")
                .start(() -> loop(metrics, exporter, intervalMillis));
    }

    private void loop(TenantMetrics metrics, MetricsExporter exporter, long intervalMillis) {
        while (running) {
            try {
                exporter.export(metrics.snapshot(), System.currentTimeMillis() * 1_000_000L);
            } catch (RuntimeException e) {
                // Telemetry never fails the poller: skip this tick, try again next time.
            }
            try {
                Thread.sleep(intervalMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Override
    public void close() {
        running = false;
        poller.interrupt();
    }
}
