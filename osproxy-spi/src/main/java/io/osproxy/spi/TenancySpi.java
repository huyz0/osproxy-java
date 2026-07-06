package io.osproxy.spi;

import io.osproxy.core.ClusterId;
import io.osproxy.core.Epoch;
import io.osproxy.core.PartitionId;
import java.util.List;
import java.util.Optional;

/**
 * The high-level tenancy contract a user implements: how to find the
 * partition in a request, how that partition is placed, and how documents
 * are marked and identified. The {@code TenancyRouter} adapts this into the
 * routing decisions the engine consumes.
 *
 * <p>Implementations must never throw unchecked exceptions from these
 * methods — every anticipated failure is a {@link SpiException} (the analog
 * of the Rust contract's "must not panic"). Methods are invoked on virtual
 * threads; blocking I/O (a placement lookup) is fine.
 */
public interface TenancySpi {

    /** Where the partition key is found in a request. */
    PartitionKeySpec partitionKeySpec();

    /** The placement (and epoch) of a partition. May block on a backend. */
    PlacementAt placementFor(PartitionId partition) throws SpiException;

    /** The physical doc-id rule, if ids are rewritten. Default: ids pass through. */
    default Optional<DocIdRule> docIdRule() {
        return Optional.empty();
    }

    /**
     * A custom OpenSearch {@code routing} value for this partition, used
     * only when {@link #docIdRule()} sets {@link DocIdRule#setRouting()}.
     * Default: none, which keeps the existing behavior of routing on the
     * partition id itself. Override this to route a shared index by
     * something other than the partition (e.g. a shard key that spreads
     * one large tenant's documents across shards instead of concentrating
     * them under a single routing value, or a sub-tenant key that keeps
     * finer-grained co-location than the partition alone would). The
     * mandatory partition filter on read is unaffected either way, this
     * only changes shard placement, never isolation.
     */
    default Optional<String> routingHint(PartitionId partition) {
        return Optional.empty();
    }

    /** Fields injected on ingest and stripped on read. Default: none. */
    default List<InjectedField> injectedFields() {
        return List.of();
    }

    /**
     * Migration write gate: may a write routed under {@code epoch} still
     * commit for this partition? Default: always (no live migration).
     */
    default boolean admitWrite(PartitionId partition, Epoch epoch) {
        return true;
    }

    /**
     * The base URL of a cluster, for sinks that resolve endpoints through
     * the SPI. Default: unknown (the sink's static configuration applies).
     */
    default Optional<String> clusterEndpoint(ClusterId cluster) {
        return Optional.empty();
    }
}
