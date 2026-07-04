package io.osproxy.sink;

import io.osproxy.core.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A per-cluster circuit breaker: after {@code failureThreshold} consecutive
 * upstream failures the circuit opens and requests are refused locally for
 * {@code openNanos} (no queue pile-up behind a dead upstream); the first
 * request after the cooldown probes half-open — success closes the circuit,
 * failure re-opens it. Deterministic via the {@link Clock} seam.
 */
public final class CircuitBreaker {

    private final Clock clock;
    private final int failureThreshold;
    private final long openNanos;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    /**
     * {@link #CLOSED}, {@link #PROBING} (one half-open probe in flight), or
     * the monotonic instant the circuit opened. Sentinels sit at the extreme
     * negative end because a monotonic clock's origin is unspecified — 0 or
     * small negative instants are legal (System.nanoTime may be negative,
     * and the test ManualClock starts at 0).
     */
    private final AtomicLong openedAt = new AtomicLong(Long.MIN_VALUE);

    private static final long CLOSED = Long.MIN_VALUE;
    private static final long PROBING = Long.MIN_VALUE + 1;

    public CircuitBreaker(Clock clock, int failureThreshold, long openNanos) {
        if (failureThreshold < 1 || openNanos < 0) {
            throw new IllegalArgumentException("threshold >= 1 and cooldown >= 0 required");
        }
        this.clock = clock;
        this.failureThreshold = failureThreshold;
        this.openNanos = openNanos;
    }

    /**
     * Whether a request may proceed. While open, everything is refused until
     * the cooldown elapses; then one caller wins the half-open probe (the
     * others keep being refused until the probe reports).
     */
    public boolean allow() {
        long opened = openedAt.get();
        if (opened == CLOSED) {
            return true;
        }
        if (opened == PROBING || clock.monotonicNanos() - opened < openNanos) {
            return false;
        }
        // Half-open: exactly one probe wins the CAS; the rest stay refused
        // until the probe reports success (closed) or failure (re-opened).
        return openedAt.compareAndSet(opened, PROBING);
    }

    /**
     * Reports a successful upstream call. Closes the circuit only from
     * CLOSED (resetting the failure streak) or PROBING (the probe passed):
     * a stale success from a request admitted before the trip must not
     * close a legitimately open circuit and skip its cooldown.
     */
    public void onSuccess() {
        consecutiveFailures.set(0);
        openedAt.compareAndSet(PROBING, CLOSED);
    }

    /** Reports a failed upstream call: may open the circuit. */
    public void onFailure() {
        if (consecutiveFailures.incrementAndGet() >= failureThreshold) {
            openedAt.set(clock.monotonicNanos());
        }
    }

    /** Whether the circuit is currently open (diagnostics). */
    public boolean isOpen() {
        return openedAt.get() != CLOSED;
    }
}
