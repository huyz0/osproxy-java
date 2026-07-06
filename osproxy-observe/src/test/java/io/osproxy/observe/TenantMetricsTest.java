package io.osproxy.observe;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TenantMetricsTest {

    @Test
    void talliesCountFailuresAndLatencyPerTenant() {
        var metrics = new TenantMetrics();
        metrics.record("acme", 200, 1_000_000);
        metrics.record("acme", 200, 2_000_000);
        metrics.record("acme", 500, 3_000_000);
        metrics.record("globex", 200, 500_000);

        String text = metrics.toPrometheusText();
        assertThat(text)
                .contains("osproxy_tenant_requests_total{tenant=\"acme\"} 3")
                .contains("osproxy_tenant_failures_total{tenant=\"acme\"} 1")
                .contains("osproxy_tenant_latency_nanos_total{tenant=\"acme\"} 6000000")
                .contains("osproxy_tenant_requests_total{tenant=\"globex\"} 1")
                .contains("osproxy_tenant_failures_total{tenant=\"globex\"} 0");
    }

    @Test
    void escapesQuotesAndBackslashesInTenantLabels() {
        var metrics = new TenantMetrics();
        metrics.record("weird\"tenant\\", 200, 100);
        assertThat(metrics.toPrometheusText()).contains("tenant=\"weird\\\"tenant\\\\\"");
    }

    @Test
    void liveEntryCountReflectsCurrentlyTrackedTenants() {
        var metrics = new TenantMetrics();
        assertThat(metrics.liveEntryCount()).isZero();
        metrics.record("acme", 200, 100);
        metrics.record("globex", 200, 100);
        assertThat(metrics.liveEntryCount()).isEqualTo(2);
    }

    @Test
    void idleTenantsAreEvictedAfterTheTtlBoundingCardinality() {
        AtomicLong nanos = new AtomicLong(0);
        Ticker ticker = nanos::get;
        var metrics = new TenantMetrics(TenantMetrics.DEFAULT_MAX_ENTRIES, Duration.ofMinutes(1), ticker);

        metrics.record("acme", 200, 100);
        assertThat(metrics.liveEntryCount()).isEqualTo(1);

        nanos.addAndGet(Duration.ofMinutes(2).toNanos());
        metrics.record("globex", 200, 100); // touches the cache, letting expiry run
        assertThat(metrics.toPrometheusText()).doesNotContain("tenant=\"acme\"");
    }

    @Test
    void aHardCapBoundsLiveEntriesEvenBeforeIdleEviction() {
        var metrics = new TenantMetrics(2, Duration.ofMinutes(15));
        metrics.record("a", 200, 1);
        metrics.record("b", 200, 1);
        metrics.record("c", 200, 1);
        assertThat(metrics.liveEntryCount()).isLessThanOrEqualTo(2);
    }
}
