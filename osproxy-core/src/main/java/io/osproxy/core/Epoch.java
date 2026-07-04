package io.osproxy.core;

/**
 * A placement generation. Every write is stamped with the epoch of the
 * placement it was routed under; a migration bumps the epoch so stale
 * in-flight writes can be refused instead of landing in the old location.
 * This slice always admits writes, but the type is part of the SPI contract
 * from day one so migration support is additive.
 */
public record Epoch(long generation) {
    public Epoch {
        if (generation < 0) {
            throw new IllegalArgumentException("epoch generation must be >= 0");
        }
    }

    /** The initial epoch of a fresh placement. */
    public static final Epoch INITIAL = new Epoch(0);

    /** The next generation, saturating at {@link Long#MAX_VALUE}. */
    public Epoch next() {
        return generation == Long.MAX_VALUE ? this : new Epoch(generation + 1);
    }
}
