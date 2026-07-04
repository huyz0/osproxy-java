package io.osproxy.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class BenchTypesTest {

    @Test
    void nearestRankPercentilesAreExactOnKnownSamples() {
        // 1..100: p50 = 50, p95 = 95, p99 = 99, max = 100.
        long[] samples = new long[100];
        for (int i = 0; i < 100; i++) {
            samples[i] = 100 - i; // reversed: fromNanos must sort
        }
        var summary = LatencySummary.fromNanos(samples);
        assertThat(summary.count()).isEqualTo(100);
        assertThat(summary.p50Nanos()).isEqualTo(50);
        assertThat(summary.p95Nanos()).isEqualTo(95);
        assertThat(summary.p99Nanos()).isEqualTo(99);
        assertThat(summary.maxNanos()).isEqualTo(100);

        // A single sample is every percentile.
        var one = LatencySummary.fromNanos(new long[] {7});
        assertThat(one.p50Nanos()).isEqualTo(7);
        assertThat(one.p99Nanos()).isEqualTo(7);

        assertThatThrownBy(() -> LatencySummary.fromNanos(new long[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(summary.render()).contains("n=100").contains("p99=");
    }

    @Test
    void profileDerivesAddedLatencyAndThroughputRatio() {
        var baseline = LatencySummary.fromNanos(new long[] {1_000_000, 2_000_000});
        var proxied = LatencySummary.fromNanos(new long[] {1_500_000, 3_000_000});
        var profile = new PerfProfile(8, baseline, proxied, 1000, 900);
        assertThat(profile.addedP50Nanos()).isEqualTo(500_000);
        assertThat(profile.addedP99Nanos()).isEqualTo(1_000_000);
        assertThat(profile.throughputRatio()).isEqualTo(0.9);
        assertThat(profile.render()).contains("c=8").contains("throughput-ratio=0.90");

        var zeroBaseline = new PerfProfile(1, baseline, proxied, 0, 100);
        assertThat(zeroBaseline.throughputRatio()).isZero();
    }
}
