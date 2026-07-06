package io.osproxy.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.osproxy.observe.MetricsExporter;
import io.osproxy.observe.TenantMetrics;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class TenantMetricsExportSchedulerTest {

    private static void awaitAtLeast(java.util.function.IntSupplier count, int target) throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(2).toNanos();
        while (count.getAsInt() < target && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(count.getAsInt()).isGreaterThanOrEqualTo(target);
    }

    @Test
    void pollsAndPushesSnapshotsUntilClosed() throws InterruptedException {
        var metrics = new TenantMetrics();
        metrics.record("acme", 200, 100);
        var exports = new CopyOnWriteArrayList<List<TenantMetrics.TenantSnapshot>>();
        MetricsExporter exporter = new MetricsExporter() {
            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public void export(List<TenantMetrics.TenantSnapshot> snapshot, long unixNanos) {
                exports.add(snapshot);
            }
        };
        var scheduler = new TenantMetricsExportScheduler(metrics, exporter, 20);
        try {
            awaitAtLeast(exports::size, 2);
        } finally {
            scheduler.close();
        }
        int sizeAfterClose = exports.size();
        assertThat(exports.get(0)).anySatisfy(s -> assertThat(s.tenant()).isEqualTo("acme"));

        // Closed: no further polling, size stops growing.
        Thread.sleep(100);
        assertThat(exports.size()).isEqualTo(sizeAfterClose);
    }

    @Test
    void aThrowingExporterNeverKillsThePoller() throws InterruptedException {
        var metrics = new TenantMetrics();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        MetricsExporter exporter = new MetricsExporter() {
            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public void export(List<TenantMetrics.TenantSnapshot> snapshot, long unixNanos) {
                calls.incrementAndGet();
                throw new RuntimeException("collector unreachable");
            }
        };
        var scheduler = new TenantMetricsExportScheduler(metrics, exporter, 10);
        try {
            awaitAtLeast(calls::get, 3);
        } finally {
            scheduler.close();
        }
    }
}
