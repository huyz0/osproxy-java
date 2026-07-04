package io.osproxy.tenancy;

import io.osproxy.core.ClusterId;
import io.osproxy.core.Epoch;
import io.osproxy.core.PartitionId;
import io.osproxy.spi.DocIdRule;
import io.osproxy.spi.InjectedField;
import io.osproxy.spi.PartitionKeySpec;
import io.osproxy.spi.PlacementAt;
import io.osproxy.spi.SpiException;
import io.osproxy.spi.TenancySpi;
import java.util.List;
import java.util.Optional;

/**
 * Decorates a {@link TenancySpi} with the {@link MigrationControl} write
 * gate: placements come from the control's table (so a cutover's flip is
 * what requests see) and {@code admitWrite} enforces the epoch/phase gate.
 * Resolution, id rules, and injection pass through to the delegate.
 */
public final class MigrationGatedTenancy implements TenancySpi {

    private final TenancySpi delegate;
    private final PlacementTable table;
    private final MigrationControl control;

    public MigrationGatedTenancy(
            TenancySpi delegate, PlacementTable table, MigrationControl control) {
        this.delegate = delegate;
        this.table = table;
        this.control = control;
    }

    @Override
    public PartitionKeySpec partitionKeySpec() {
        return delegate.partitionKeySpec();
    }

    @Override
    public PlacementAt placementFor(PartitionId partition) throws SpiException {
        return table.lookup(partition);
    }

    @Override
    public Optional<DocIdRule> docIdRule() {
        return delegate.docIdRule();
    }

    @Override
    public List<InjectedField> injectedFields() {
        return delegate.injectedFields();
    }

    @Override
    public boolean admitWrite(PartitionId partition, Epoch epoch) {
        return control.admitWrite(partition, epoch);
    }

    @Override
    public Optional<String> clusterEndpoint(ClusterId cluster) {
        return delegate.clusterEndpoint(cluster);
    }
}
