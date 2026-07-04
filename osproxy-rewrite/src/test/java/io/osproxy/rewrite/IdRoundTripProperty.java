package io.osproxy.rewrite;

import static org.assertj.core.api.Assertions.assertThat;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.NotBlank;
import net.jqwik.api.constraints.StringLength;

/**
 * The isolation-critical invariant: mapping a logical id to physical and
 * back is the identity, for any partition and id, under the standard
 * partition-prefixed template.
 */
class IdRoundTripProperty {

    private static final String TEMPLATE = "{partition}:{id}";

    @Property
    void logicalPhysicalRoundTripIsIdentity(
            @ForAll @AlphaChars @NotBlank @StringLength(min = 1, max = 24) String partition,
            @ForAll @NotBlank @StringLength(min = 1, max = 64) String logicalId)
            throws Exception {
        String physical = DocIds.mapLogicalToPhysical(TEMPLATE, partition, logicalId);
        assertThat(DocIds.mapPhysicalToLogical(TEMPLATE, partition, physical))
                .contains(logicalId);
    }

    @Property
    void anotherPartitionsIdNeverInverts(
            @ForAll @AlphaChars @NotBlank @StringLength(min = 1, max = 24) String partitionA,
            @ForAll @AlphaChars @NotBlank @StringLength(min = 1, max = 24) String partitionB,
            @ForAll @AlphaChars @NotBlank @StringLength(min = 1, max = 24) String logicalId)
            throws Exception {
        // Only meaningful when the partitions differ and neither frames the other.
        if (partitionA.equals(partitionB)
                || (partitionB + ":" + logicalId).startsWith(partitionA + ":")) {
            return;
        }
        String physicalB = DocIds.mapLogicalToPhysical(TEMPLATE, partitionB, logicalId);
        assertThat(DocIds.mapPhysicalToLogical(TEMPLATE, partitionA, physicalB)).isEmpty();
    }
}
