package io.osproxy.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class EndpointKindTest {

    @Test
    void writeEndpointsAreExactlyTheMutatingOnes() {
        Set<EndpointKind> writes = Set.of(
                EndpointKind.INGEST_DOC, EndpointKind.INGEST_BULK, EndpointKind.DELETE_BY_ID,
                EndpointKind.DELETE_BY_QUERY);
        for (EndpointKind kind : EndpointKind.values()) {
            assertThat(kind.isWrite()).isEqualTo(writes.contains(kind));
        }
    }

    @Test
    void onlyAdminAndUnknownAreTenancyBlind() {
        for (EndpointKind kind : EndpointKind.values()) {
            boolean blind = kind == EndpointKind.ADMIN || kind == EndpointKind.UNKNOWN;
            assertThat(kind.isTenancyAware()).isEqualTo(!blind);
        }
    }

    @Test
    void wireNamesAreStableKebabCase() {
        for (EndpointKind kind : EndpointKind.values()) {
            assertThat(kind.wireName()).matches("[a-z]+(-[a-z]+)*");
        }
        assertThat(EndpointKind.INGEST_BULK.wireName()).isEqualTo("ingest-bulk");
    }
}
