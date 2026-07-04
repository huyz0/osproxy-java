package io.osproxy.core;

/**
 * The clock seam: production code never reads the wall clock directly, it
 * takes a {@code Clock}. Production wires {@link SystemClock}; tests wire
 * {@link ManualClock} and advance it explicitly, so timeouts and TTLs are
 * deterministic.
 */
public interface Clock {
    /** Monotonic nanoseconds since an unspecified origin (relative use only). */
    long monotonicNanos();
}
