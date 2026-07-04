package io.osproxy.sink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.osproxy.core.ManualClock;
import org.junit.jupiter.api.Test;

class CircuitBreakerTest {

    @Test
    void opensAfterConsecutiveFailuresAndRefusesWhileOpen() {
        var clock = new ManualClock();
        var breaker = new CircuitBreaker(clock, 3, 1_000);

        for (int i = 0; i < 3; i++) {
            assertThat(breaker.allow()).isTrue();
            breaker.onFailure();
        }
        assertThat(breaker.isOpen()).isTrue();
        assertThat(breaker.allow()).isFalse();

        // Cooldown not yet elapsed.
        clock.advanceNanos(999);
        assertThat(breaker.allow()).isFalse();
    }

    @Test
    void halfOpenAdmitsExactlyOneProbeAndSuccessCloses() {
        var clock = new ManualClock();
        var breaker = new CircuitBreaker(clock, 1, 1_000);
        breaker.onFailure();
        clock.advanceNanos(1_000);

        assertThat(breaker.allow()).isTrue(); // the probe
        assertThat(breaker.allow()).isFalse(); // everyone else waits
        breaker.onSuccess();
        assertThat(breaker.allow()).isTrue();
        assertThat(breaker.isOpen()).isFalse();
    }

    @Test
    void aFailedProbeReopensTheCircuit() {
        var clock = new ManualClock();
        var breaker = new CircuitBreaker(clock, 1, 1_000);
        breaker.onFailure();
        clock.advanceNanos(1_000);
        assertThat(breaker.allow()).isTrue();
        breaker.onFailure();
        assertThat(breaker.allow()).isFalse();
        clock.advanceNanos(1_000);
        assertThat(breaker.allow()).isTrue();
    }

    @Test
    void aSuccessResetsTheConsecutiveCount() {
        var clock = new ManualClock();
        var breaker = new CircuitBreaker(clock, 2, 1_000);
        breaker.onFailure();
        breaker.onSuccess();
        breaker.onFailure();
        assertThat(breaker.isOpen()).isFalse();
        assertThat(breaker.allow()).isTrue();
    }

    @Test
    void constructorValidates() {
        var clock = new ManualClock();
        assertThatThrownBy(() -> new CircuitBreaker(clock, 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CircuitBreaker(clock, 1, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
