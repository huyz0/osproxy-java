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
 *
 * <p>{@code cutover} touches two independent structures — the phase map and
 * {@code table}'s epoch — that must move together: a concurrent {@link
 * #admitWrite} landing between the two writes could otherwise observe
 * phase=DRAINING (not yet CUTOVER, so treated as a normal write) with the
 * epoch already bumped, admitting a write during the exact window invariant
 * 2 says must refuse everything. Each partition's transitions and reads are
 * synchronized on that partition's own lock so the two structures are never
 * observed out of step; per-partition (not a single class-wide lock) so
 * unrelated tenants' write paths never contend with each other over a
 * migration that isn't theirs.
 */
public final class MigrationControl {

    private final PlacementTable table;
    private final Map<PartitionId, MigrationPhase> phases = new ConcurrentHashMap<>();
    private final Map<PartitionId, Object> locks = new ConcurrentHashMap<>();

    public MigrationControl(PlacementTable table) {
        this.table = table;
    }

    private Object lockFor(PartitionId partition) {
        return locks.computeIfAbsent(partition, p -> new Object());
    }

    /** The partition's phase ({@code SETTLED} when never migrated). */
    public MigrationPhase phase(PartitionId partition) {
        return phases.getOrDefault(partition, MigrationPhase.SETTLED);
    }

    /** Announces a migration: writes continue, the fleet converges. */
    public void beginDrain(PartitionId partition) {
        synchronized (lockFor(partition)) {
            requirePhase(partition, MigrationPhase.SETTLED, "beginDrain");
            phases.put(partition, MigrationPhase.DRAINING);
        }
    }

    /**
     * Flips the placement: the epoch bumps and every write holds until
     * {@link #complete}. Legal only from {@code DRAINING} — a cutover without
     * a drain window would race in-flight writes.
     */
    public void cutover(PartitionId partition, Placement newPlacement) {
        synchronized (lockFor(partition)) {
            requirePhase(partition, MigrationPhase.DRAINING, "cutover");
            table.put(partition, newPlacement); // bumps the epoch
            phases.put(partition, MigrationPhase.CUTOVER);
        }
    }

    /** The new placement is live: writes resume at the new epoch. */
    public void complete(PartitionId partition) {
        synchronized (lockFor(partition)) {
            requirePhase(partition, MigrationPhase.CUTOVER, "complete");
            phases.put(partition, MigrationPhase.SETTLED);
        }
    }

    /**
     * The write gate: refuse during cutover; otherwise admit only writes
     * routed under the partition's current epoch.
     */
    public boolean admitWrite(PartitionId partition, Epoch epoch) {
        synchronized (lockFor(partition)) {
            if (phase(partition) == MigrationPhase.CUTOVER) {
                return false;
            }
            try {
                return table.lookup(partition).epoch().equals(epoch);
            } catch (SpiException e) {
                return false; // no placement: nothing to admit against
            }
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
