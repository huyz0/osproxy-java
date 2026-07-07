package io.osproxy.tenancy;

import com.fasterxml.jackson.databind.JsonNode;
import io.osproxy.core.IndexName;
import io.osproxy.core.PartitionId;
import io.osproxy.core.Target;
import io.osproxy.spi.BodyTransform;
import io.osproxy.spi.DocIdRule;
import io.osproxy.spi.InjectedField;
import io.osproxy.spi.Placement;
import io.osproxy.spi.PlacementAt;
import io.osproxy.spi.RequestCtx;
import io.osproxy.spi.RouteDecision;
import io.osproxy.spi.SpiException;
import io.osproxy.spi.TenancySpi;
import java.util.List;
import java.util.Optional;

/**
 * Adapts a user's {@link TenancySpi} into the {@link RouteDecision} the
 * engine consumes: resolve the partition, look up its placement, derive the
 * physical target and body transform — and fail closed on the isolation
 * hazards (a shared index whose id rule does not embed the partition, or a
 * shared index with no injected fields at all).
 */
public final class TenancyRouter {

    private final TenancySpi spi;

    public TenancyRouter(TenancySpi spi) {
        if (spi == null) {
            throw new IllegalArgumentException("spi must be non-null");
        }
        this.spi = spi;
    }

    /** The wrapped SPI (the engine consults it for read-path symmetry). */
    public TenancySpi spi() {
        return spi;
    }

    /**
     * Routes one request (or one demuxed bulk document — {@code body} is the
     * per-document parse, null when bodyless).
     */
    public RouteDecision route(RequestCtx ctx, JsonNode body) throws SpiException {
        PartitionId partition = PartitionResolver.resolve(spi.partitionKeySpec(), ctx, body);
        PlacementAt at = spi.placementFor(partition);
        return decisionFor(ctx, partition, at);
    }

    /**
     * Routes a cursor request (scroll/PIT continue or close): these carry no
     * index in the path, and the target index is irrelevant — the upstream
     * call is cluster-scoped. The partition still resolves (isolation fields
     * and id rules shape the batches), but a dedicated-cluster placement
     * falls back to a placeholder index instead of failing closed.
     */
    public RouteDecision routeCursor(RequestCtx ctx) throws SpiException {
        PartitionId partition = PartitionResolver.resolve(spi.partitionKeySpec(), ctx, null);
        PlacementAt at = spi.placementFor(partition);
        Placement placement = at.placement();
        IndexName physicalIndex = switch (placement) {
            case Placement.DedicatedIndex(var ignored, IndexName index) -> index;
            case Placement.SharedIndex(var ignored, IndexName index, var alsoIgnored) -> index;
            case Placement.DedicatedCluster ignored -> ctx.logicalIndex()
                    .map(IndexName::new)
                    .orElse(new IndexName("cursor"));
        };
        Target target = new Target(
                placement.cluster(), physicalIndex, spi.clusterEndpoint(placement.cluster()),
                spi.upstreamCredentials(placement.cluster()));
        return new RouteDecision(target, partition, transformFor(placement), at.epoch());
    }

    /** Builds the decision from an already-resolved placement. */
    public RouteDecision decisionFor(RequestCtx ctx, PartitionId partition, PlacementAt at)
            throws SpiException {
        Placement placement = at.placement();
        IndexName physicalIndex = physicalIndex(ctx, placement);
        Target target = new Target(
                placement.cluster(),
                physicalIndex,
                spi.clusterEndpoint(placement.cluster()),
                spi.upstreamCredentials(placement.cluster()));
        return new RouteDecision(target, partition, transformFor(placement), at.epoch());
    }

    /**
     * The physical index: the placement's own for dedicated/shared index
     * modes; the client's logical index for a dedicated cluster (the whole
     * cluster belongs to the partition, indices pass through).
     */
    private static IndexName physicalIndex(RequestCtx ctx, Placement placement)
            throws SpiException {
        return switch (placement) {
            case Placement.DedicatedIndex(var ignored, IndexName index) -> index;
            case Placement.SharedIndex(var ignored, IndexName index, var alsoIgnored) -> index;
            case Placement.DedicatedCluster ignored -> ctx.logicalIndex()
                    .map(IndexName::new)
                    .orElseThrow(() -> new SpiException.UnsupportedEndpoint(ctx.endpoint()));
        };
    }

    /**
     * The body transform, with the fail-closed shared-index checks: injected
     * fields must exist (else tenants are indistinguishable) and the id rule
     * must embed the partition (else physical ids can collide).
     */
    private BodyTransform transformFor(Placement placement) throws SpiException {
        Optional<DocIdRule> rule = spi.docIdRule();
        if (placement instanceof Placement.SharedIndex(var c, var i, List<InjectedField> inject)) {
            if (inject.isEmpty()) {
                throw new SpiException.IdRuleMissingPartition();
            }
            if (rule.isPresent() && !rule.get().referencesPartition()) {
                throw new SpiException.IdRuleMissingPartition();
            }
            return rule.<BodyTransform>map(r -> new BodyTransform.Both(inject, r))
                    .orElseGet(() -> new BodyTransform.Inject(inject));
        }
        return rule.<BodyTransform>map(BodyTransform.ConstructId::new)
                .orElse(BodyTransform.None.INSTANCE);
    }
}
