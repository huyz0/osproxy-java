package io.osproxy.core;

/** The one sanctioned place that touches the real clock. */
public final class SystemClock implements Clock {
    @Override
    public long monotonicNanos() {
        return System.nanoTime();
    }
}
