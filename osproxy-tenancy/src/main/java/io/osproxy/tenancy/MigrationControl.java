package io.osproxy.tenancy;

import io.osproxy.core.Epoch;
import io.osproxy.core.PartitionId;
import io.osproxy.spi.MigrationPhase;
import io.osproxy.spi.Placement;
import io.osproxy.spi.SpiException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The migration state machine over a {@link PlacementTable}: per partition,
 * {@code SETTLED → DRAINING → CUTOVER → SETTLED'}, where the cutover flips
 * the placement (bumping the epoch) and holds every write until completed.
 * The write-gate invariants (the Rust project's INV-M1..M4):
 *
 * <ol>
 *   <li>settled/draining writes are admitted at the current epoch;
 *   <li>during cutover no write is admitted at any epoch;
 *   <li>after cutover completes, writes at the old epoch are refused
 *       (stale) and writes at the new epoch are admitted;
 *   <li>reads are never gated by migration state.
 * </ol>
 */
public final class MigrationControl {

    private final PlacementTable table;
    private final Map<PartitionId, MigrationPhase> phases = new ConcurrentHashMap<>();

    public MigrationControl(PlacementTable table) {
        this.table = table;
    }

    /** The partition's phase ({@code SETTLED} when never migrated). */
    public MigrationPhase phase(PartitionId partition) {
        return phases.getOrDefault(partition, MigrationPhase.SETTLED);
    }

    /** Announces a migration: writes continue, the fleet converges. */
    public void beginDrain(PartitionId partition) {
        requirePhase(partition, MigrationPhase.SETTLED, "beginDrain");
        phases.put(partition, MigrationPhase.DRAINING);
    }

    /**
     * Flips the placement: the epoch bumps and every write holds until
     * {@link #complete}. Legal only from {@code DRAINING} — a cutover without
     * a drain window would race in-flight writes.
     */
    public void cutover(PartitionId partition, Placement newPlacement) {
        requirePhase(partition, MigrationPhase.DRAINING, "cutover");
        table.put(partition, newPlacement); // bumps the epoch
        phases.put(partition, MigrationPhase.CUTOVER);
    }

    /** The new placement is live: writes resume at the new epoch. */
    public void complete(PartitionId partition) {
        requirePhase(partition, MigrationPhase.CUTOVER, "complete");
        phases.put(partition, MigrationPhase.SETTLED);
    }

    /**
     * The write gate: refuse during cutover; otherwise admit only writes
     * routed under the partition's current epoch.
     */
    public boolean admitWrite(PartitionId partition, Epoch epoch) {
        if (phase(partition) == MigrationPhase.CUTOVER) {
            return false;
        }
        try {
            return table.lookup(partition).epoch().equals(epoch);
        } catch (SpiException e) {
            return false; // no placement: nothing to admit against
        }
    }

    private void requirePhase(PartitionId partition, MigrationPhase expected, String transition) {
        MigrationPhase actual = phase(partition);
        if (actual != expected) {
            throw new IllegalStateException(
                    transition + " requires " + expected + " but partition is " + actual);
        }
    }
}
