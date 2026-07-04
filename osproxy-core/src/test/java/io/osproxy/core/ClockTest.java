package io.osproxy.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ClockTest {

    @Test
    void manualClockOnlyMovesWhenAdvanced() {
        ManualClock clock = new ManualClock();
        long before = clock.monotonicNanos();
        assertThat(clock.monotonicNanos()).isEqualTo(before);
        clock.advanceNanos(1_000);
        assertThat(clock.monotonicNanos()).isEqualTo(before + 1_000);
        assertThatThrownBy(() -> clock.advanceNanos(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void systemClockIsMonotonic() {
        SystemClock clock = new SystemClock();
        long a = clock.monotonicNanos();
        long b = clock.monotonicNanos();
        assertThat(b).isGreaterThanOrEqualTo(a);
    }
}
