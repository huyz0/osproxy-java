package io.osproxy.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.osproxy.core.ClusterId;
import io.osproxy.core.Epoch;
import io.osproxy.core.IndexName;
import io.osproxy.core.PartitionId;
import io.osproxy.spi.MigrationPhase;
import io.osproxy.spi.Placement;
import org.junit.jupiter.api.Test;

/** The INV-M1..M4 write-gate invariants as a full migration simulation. */
class MigrationControlTest {

    private static final PartitionId ACME = new PartitionId("acme");
    private static final Placement OLD =
            new Placement.DedicatedIndex(new ClusterId("c1"), new IndexName("old"));
    private static final Placement NEW =
            new Placement.DedicatedIndex(new ClusterId("c2"), new IndexName("new"));

    @Test
    void theFullMigrationLifecycleGatesWritesCorrectly() throws Exception {
        var table = new PlacementTable();
        table.put(ACME, OLD);
        var control = new MigrationControl(table);
        Epoch epoch0 = table.lookup(ACME).epoch();

        // INV-M1: settled writes admitted at the current epoch only.
        assertThat(control.admitWrite(ACME, epoch0)).isTrue();
        assertThat(control.admitWrite(ACME, epoch0.next())).isFalse();

        // Draining: writes still admitted at the current epoch.
        control.beginDrain(ACME);
        assertThat(control.phase(ACME)).isEqualTo(MigrationPhase.DRAINING);
        assertThat(control.admitWrite(ACME, epoch0)).isTrue();

        // INV-M2: during cutover nothing is admitted, any epoch.
        control.cutover(ACME, NEW);
        Epoch epoch1 = table.lookup(ACME).epoch();
        assertThat(epoch1).isEqualTo(epoch0.next());
        assertThat(control.admitWrite(ACME, epoch0)).isFalse();
        assertThat(control.admitWrite(ACME, epoch1)).isFalse();
        assertThat(table.lookup(ACME).placement()).isEqualTo(NEW);

        // INV-M3: after completion, the old epoch is stale, the new admits.
        control.complete(ACME);
        assertThat(control.phase(ACME)).isEqualTo(MigrationPhase.SETTLED);
        assertThat(control.admitWrite(ACME, epoch0)).isFalse();
        assertThat(control.admitWrite(ACME, epoch1)).isTrue();
    }

    @Test
    void illegalTransitionsAreRefusedLoudly() {
        var table = new PlacementTable();
        table.put(ACME, OLD);
        var control = new MigrationControl(table);

        assertThatThrownBy(() -> control.cutover(ACME, NEW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DRAINING");
        assertThatThrownBy(() -> control.complete(ACME))
                .isInstanceOf(IllegalStateException.class);
        control.beginDrain(ACME);
        assertThatThrownBy(() -> control.beginDrain(ACME))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unknownPartitionsAdmitNothing() {
        var control = new MigrationControl(new PlacementTable());
        assertThat(control.admitWrite(new PartitionId("ghost"), Epoch.INITIAL)).isFalse();
    }
}
