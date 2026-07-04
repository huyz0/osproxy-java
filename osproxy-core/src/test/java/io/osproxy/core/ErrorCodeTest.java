package io.osproxy.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ErrorCodeTest {

    @Test
    void everyCodeHasAValidStatusAndSnakeCaseName() {
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(code.httpStatus()).isBetween(400, 599);
            assertThat(code.wireName()).matches("[a-z]+(_[a-z]+)*");
        }
    }

    @Test
    void wireContractMatchesTheRustProxy() {
        assertThat(ErrorCode.STALE_EPOCH.httpStatus()).isEqualTo(409);
        assertThat(ErrorCode.PARTITION_UNRESOLVED.httpStatus()).isEqualTo(400);
        assertThat(ErrorCode.PLACEMENT_BACKEND_UNAVAILABLE.httpStatus()).isEqualTo(503);
        assertThat(ErrorCode.OVERLOADED.httpStatus()).isEqualTo(429);
    }

    @Test
    void jsonBodyIsShapeOnly() {
        assertThat(ErrorCode.AUTH_FAILED.toJsonBody()).isEqualTo("{\"error\":\"auth_failed\"}");
    }
}
