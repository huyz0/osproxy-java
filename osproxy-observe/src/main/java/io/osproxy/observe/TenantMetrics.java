package io.osproxy.observe;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.concurrent.atomic.LongAdder;

/**
 * Opt-in, per-tenant request counters (count, failures, latency). This is
 * the one type in this module allowed to carry a tenant value; {@link
 * Metrics} stays shape-only on purpose, and this exists specifically to
 * answer "which tenant is failing or slow", a question the aggregate can't.
 * Off by default (see {@code Observability#withTenantMetrics}); a
 * deployment that never enables it never allocates one of these.
 *
 * <p>Cardinality is bounded two ways: a tenant idle for {@code idleTtl} is
 * evicted, and a hard {@code maxEntries} cap protects against a burst of
 * one-off or adversarial tenant ids before the TTL would ever apply.
 * Exported cardinality is therefore bounded by how many tenants are live
 * right now, never by how many have ever existed — realistic for the target
 * shape (thousands of tenants arriving over minutes, not millions at once).
 *
 * <p>Request size isn't tracked here: several ingress paths (passthrough,
 * streamed bulk) deliberately never materialize a byte count for the whole
 * request (see {@code AppHandler}'s streaming paths), so a size field would
 * only be populated some of the time. Count/failures/latency are recorded
 * on every path uniformly.
 */
public final class TenantMetrics {

    /** Bounded default: 50k live tenants, 15 minute idle eviction. */
    public static final int DEFAULT_MAX_ENTRIES = 50_000;

    /** The default idle window before a tenant with no traffic is evicted. */
    public static final Duration DEFAULT_IDLE_TTL = Duration.ofMinutes(15);

    private final Cache<String, Counters> byTenant;

    public TenantMetrics() {
        this(DEFAULT_MAX_ENTRIES, DEFAULT_IDLE_TTL);
    }

    public TenantMetrics(int maxEntries, Duration idleTtl) {
        this(maxEntries, idleTtl, Ticker.systemTicker());
    }

    /** Test seam: an explicit {@link Ticker} lets idle-eviction be tested without sleeping. */
    TenantMetrics(int maxEntries, Duration idleTtl, Ticker ticker) {
        this.byTenant = Caffeine.newBuilder()
                .maximumSize(maxEntries)
                .expireAfterAccess(idleTtl)
                .ticker(ticker)
                // Runs eviction/expiry maintenance synchronously on the
                // calling thread instead of Caffeine's default background
                // pool: these are cheap counter evictions, not worth an
                // extra thread, and it makes cardinality bounds observable
                // right after the write that crossed them, not eventually.
                .executor(Runnable::run)
                .build();
    }

    /** Tallies one completed request for this tenant. */
    public void record(String tenant, int status, long durationNanos) {
        Counters c = byTenant.get(tenant, t -> new Counters());
        c.count.increment();
        if (status >= 400) {
            c.failures.increment();
        }
        c.durationNanos.add(durationNanos);
    }

    /** A Prometheus text snapshot of every currently-live tenant. Labels carry only tenant ids. */
    public String toPrometheusText() {
        StringBuilder sb = new StringBuilder();
        byTenant.asMap().forEach((tenant, c) -> {
            String label = "tenant=\"" + escape(tenant) + "\"";
            sb.append("osproxy_tenant_requests_total{").append(label).append("} ")
                    .append(c.count.sum()).append('\n');
            sb.append("osproxy_tenant_failures_total{").append(label).append("} ")
                    .append(c.failures.sum()).append('\n');
            sb.append("osproxy_tenant_latency_nanos_total{").append(label).append("} ")
                    .append(c.durationNanos.sum()).append('\n');
        });
        return sb.toString();
    }

    /** Live tenants right now (bounded by {@code maxEntries}, not all-time count). */
    public long liveEntryCount() {
        return byTenant.estimatedSize();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static final class Counters {
        private final LongAdder count = new LongAdder();
        private final LongAdder failures = new LongAdder();
        private final LongAdder durationNanos = new LongAdder();
    }
}
