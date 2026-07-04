package io.osproxy.spi;

import static org.assertj.core.api.Assertions.assertThat;

import io.osproxy.core.EndpointKind;
import io.osproxy.core.ErrorCode;
import io.osproxy.core.PartitionId;
import org.junit.jupiter.api.Test;

class SpiExceptionTest {

    @Test
    void everyVariantMapsToAStableWireCode() {
        assertThat(new SpiException.PartitionUnresolved().errorCode())
                .isEqualTo(ErrorCode.PARTITION_UNRESOLVED);
        assertThat(new SpiException.PlacementMissing(new PartitionId("acme")).errorCode())
                .isEqualTo(ErrorCode.PLACEMENT_MISSING);
        assertThat(new SpiException.PlacementBackend(true).errorCode())
                .isEqualTo(ErrorCode.PLACEMENT_BACKEND_UNAVAILABLE);
        assertThat(new SpiException.UnsupportedEndpoint(EndpointKind.CURSOR).errorCode())
                .isEqualTo(ErrorCode.UNSUPPORTED_ENDPOINT);
        assertThat(new SpiException.PrincipalAttrMissing("tenant").errorCode())
                .isEqualTo(ErrorCode.PARTITION_UNRESOLVED);
        assertThat(new SpiException.HeaderMissing("x-tenant").errorCode())
                .isEqualTo(ErrorCode.PARTITION_UNRESOLVED);
        assertThat(new SpiException.IdRuleMissingPartition().errorCode())
                .isEqualTo(ErrorCode.UNSUPPORTED_ENDPOINT);
    }

    @Test
    void messagesAreShapeOnly() {
        var e = new SpiException.PlacementMissing(new PartitionId("secret-tenant"));
        assertThat(e.getMessage()).doesNotContain("secret-tenant");
        // The value is still available programmatically for diagnostics.
        assertThat(e.partition().value()).isEqualTo("secret-tenant");
    }
}
