package io.osproxy.core;

import java.util.concurrent.atomic.AtomicLong;

/** A test clock advanced explicitly; time never moves on its own. */
public final class ManualClock implements Clock {
    private final AtomicLong nanos = new AtomicLong();

    @Override
    public long monotonicNanos() {
        return nanos.get();
    }

    /** Advances the clock by {@code deltaNanos} (must be non-negative). */
    public void advanceNanos(long deltaNanos) {
        if (deltaNanos < 0) {
            throw new IllegalArgumentException("clock can only move forward");
        }
        nanos.addAndGet(deltaNanos);
    }
}
