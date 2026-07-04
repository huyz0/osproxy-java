package io.osproxy.bench;

/**
 * One proxy-vs-baseline measurement at one concurrency level: what the
 * proxy adds (per-percentile deltas) and what it sustains (throughput).
 * The added latencies are the honest cost of the tenancy transforms; the
 * throughput ratio says whether the proxy scales with its baseline.
 *
 * @param concurrency parallel clients during the measurement
 * @param baseline direct-to-upstream latencies
 * @param proxied through-the-proxy latencies
 * @param baselineOpsPerSecond direct throughput
 * @param proxiedOpsPerSecond through-proxy throughput
 */
public record PerfProfile(
        int concurrency,
        LatencySummary baseline,
        LatencySummary proxied,
        double baselineOpsPerSecond,
        double proxiedOpsPerSecond) {

    /** What the proxy adds at the median (may be negative in noise). */
    public long addedP50Nanos() {
        return proxied.p50Nanos() - baseline.p50Nanos();
    }

    /** What the proxy adds at the 99th percentile. */
    public long addedP99Nanos() {
        return proxied.p99Nanos() - baseline.p99Nanos();
    }

    /** Proxied throughput as a fraction of the baseline's. */
    public double throughputRatio() {
        return baselineOpsPerSecond == 0 ? 0 : proxiedOpsPerSecond / baselineOpsPerSecond;
    }

    /** The report block for one concurrency level. */
    public String render() {
        return "c=" + concurrency + "\n"
                + "  baseline " + baseline.render()
                + String.format(" %.0f ops/s%n", baselineOpsPerSecond)
                + "  proxied  " + proxied.render()
                + String.format(" %.0f ops/s%n", proxiedOpsPerSecond)
                + String.format(
                        "  added    p50=%.2fms p99=%.2fms throughput-ratio=%.2f",
                        addedP50Nanos() / 1e6, addedP99Nanos() / 1e6, throughputRatio());
    }
}
