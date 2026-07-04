package io.osproxy.server;

import io.osproxy.core.ClusterId;
import io.osproxy.core.Epoch;
import io.osproxy.core.IndexName;
import io.osproxy.core.PartitionId;
import io.osproxy.spi.DocIdRule;
import io.osproxy.spi.InjectedField;
import io.osproxy.spi.InjectedValue;
import io.osproxy.spi.PartitionKeySpec;
import io.osproxy.spi.Placement;
import io.osproxy.spi.PlacementAt;
import io.osproxy.spi.TenancySpi;
import java.util.List;
import java.util.Optional;

/**
 * The reference tenancy: every tenant shares one physical index on one
 * cluster (highest density), isolated by an injected {@code _tenant} marker
 * and partition-prefixed doc ids. The partition comes from the authenticated
 * principal's {@code tenant} attribute — never from anything the client can
 * type freely once real tokens are configured.
 */
public final class ReferenceTenancy implements TenancySpi {

    /** The injected isolation marker's field name. */
    public static final String TENANT_FIELD = "_tenant";

    private final PlacementAt placement;

    public ReferenceTenancy(ClusterId cluster, IndexName sharedIndex) {
        this.placement = new PlacementAt(
                new Placement.SharedIndex(cluster, sharedIndex, List.of(
                        new InjectedField(TENANT_FIELD, InjectedValue.PartitionIdValue.INSTANCE))),
                Epoch.INITIAL);
    }

    @Override
    public PartitionKeySpec partitionKeySpec() {
        return new PartitionKeySpec.PrincipalAttr("tenant");
    }

    @Override
    public PlacementAt placementFor(PartitionId partition) {
        return placement;
    }

    @Override
    public Optional<DocIdRule> docIdRule() {
        return Optional.of(new DocIdRule("{partition}:{id}", true));
    }
}
