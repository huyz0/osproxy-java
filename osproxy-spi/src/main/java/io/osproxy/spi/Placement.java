package io.osproxy.spi;

import io.osproxy.core.ClusterId;
import io.osproxy.core.IndexName;
import java.util.List;

/**
 * Where a partition physically lives — the isolation/density trade-off, as a
 * sealed hierarchy so a switch over placements is exhaustive.
 */
public sealed interface Placement {

    /** The partition owns a whole cluster (strongest isolation). */
    record DedicatedCluster(ClusterId cluster) implements Placement {}

    /** The partition owns an index on a shared cluster. */
    record DedicatedIndex(ClusterId cluster, IndexName index) implements Placement {}

    /**
     * The partition shares a physical index with others, isolated by the
     * fields injected on ingest and stripped (and filtered on) on read.
     */
    record SharedIndex(ClusterId cluster, IndexName index, List<InjectedField> inject)
            implements Placement {
        public SharedIndex {
            inject = List.copyOf(inject);
        }
    }

    /** The hosting cluster, whichever mode this is. */
    default ClusterId cluster() {
        return switch (this) {
            case DedicatedCluster(ClusterId c) -> c;
            case DedicatedIndex(ClusterId c, IndexName _) -> c;
            case SharedIndex(ClusterId c, IndexName _, List<InjectedField> _) -> c;
        };
    }
}
