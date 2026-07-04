package io.osproxy.bench;

import java.util.Arrays;

/**
 * Nearest-rank percentiles over a sample of latencies — the deterministic
 * vocabulary the e2e perf harness reports in (the Rust project's
 * osproxy-bench analog). Pure math, no clocks: the harness measures, this
 * summarizes.
 *
 * @param count sample size
 * @param p50Nanos the median
 * @param p95Nanos the 95th percentile
 * @param p99Nanos the 99th percentile
 * @param maxNanos the worst sample
 */
public record LatencySummary(long count, long p50Nanos, long p95Nanos, long p99Nanos, long maxNanos) {

    /** Summarizes raw samples (nanos); refuses an empty sample. */
    public static LatencySummary fromNanos(long[] samples) {
        if (samples == null || samples.length == 0) {
            throw new IllegalArgumentException("need at least one sample");
        }
        long[] sorted = samples.clone();
        Arrays.sort(sorted);
        return new LatencySummary(
                sorted.length,
                nearestRank(sorted, 50),
                nearestRank(sorted, 95),
                nearestRank(sorted, 99),
                sorted[sorted.length - 1]);
    }

    /** The nearest-rank percentile: ceil(p/100 * n)-th smallest sample. */
    private static long nearestRank(long[] sorted, int percentile) {
        int rank = (int) Math.ceil(percentile / 100.0 * sorted.length);
        return sorted[Math.max(0, rank - 1)];
    }

    /** One human-readable line, milliseconds with two decimals. */
    public String render() {
        return String.format(
                "n=%d p50=%.2fms p95=%.2fms p99=%.2fms max=%.2fms",
                count, p50Nanos / 1e6, p95Nanos / 1e6, p99Nanos / 1e6, maxNanos / 1e6);
    }
}
