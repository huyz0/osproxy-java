package io.osproxy.tenancy;

import io.osproxy.core.Epoch;
import io.osproxy.core.PartitionId;
import io.osproxy.spi.Placement;
import io.osproxy.spi.PlacementAt;
import io.osproxy.spi.SpiException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An in-memory partition→placement map with per-partition epochs — the
 * building block for a static or reference tenancy, and the local cache a
 * distributed placement backend would fill. A default placement, when set,
 * answers unknown partitions (the "every tenant shares one index" mode).
 */
public final class PlacementTable {

    private record Entry(Placement placement, Epoch epoch) {}

    private final ConcurrentHashMap<PartitionId, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicReference<Placement> defaultPlacement = new AtomicReference<>();

    /** Sets (or replaces) a partition's placement, bumping its epoch. */
    public void put(PartitionId partition, Placement placement) {
        entries.compute(partition, (p, prior) -> new Entry(
                placement, prior == null ? Epoch.INITIAL : prior.epoch().next()));
    }

    /** Sets the placement answering partitions with no explicit entry. */
    public void setDefault(Placement placement) {
        defaultPlacement.set(placement);
    }

    /** Looks a partition up, falling back to the default. */
    public PlacementAt lookup(PartitionId partition) throws SpiException {
        Entry entry = entries.get(partition);
        if (entry != null) {
            return new PlacementAt(entry.placement(), entry.epoch());
        }
        Placement fallback = defaultPlacement.get();
        if (fallback != null) {
            return new PlacementAt(fallback, Epoch.INITIAL);
        }
        throw new SpiException.PlacementMissing(partition);
    }

    /** A snapshot of the explicit entries (diagnostics only). */
    public Map<PartitionId, Placement> snapshot() {
        Map<PartitionId, Placement> out = new java.util.HashMap<>();
        entries.forEach((p, e) -> out.put(p, e.placement()));
        return Map.copyOf(out);
    }
}
