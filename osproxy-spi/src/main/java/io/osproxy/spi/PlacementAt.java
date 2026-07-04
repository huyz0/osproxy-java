package io.osproxy.spi;

import io.osproxy.core.Epoch;

/**
 * A placement together with the epoch it was read at. The epoch travels with
 * every write derived from this placement so a migration can refuse stale
 * writes (this slice always admits; the pairing is the forward-compatible
 * contract).
 */
public record PlacementAt(Placement placement, Epoch epoch) {
    public PlacementAt {
        if (placement == null || epoch == null) {
            throw new IllegalArgumentException("placement and epoch must be non-null");
        }
    }
}
