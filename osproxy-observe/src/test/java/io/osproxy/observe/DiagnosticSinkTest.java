package io.osproxy.observe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

class DiagnosticSinkTest {

    @Test
    void theNoopSinkIsDisabledAndNeverThrows() {
        assertThat(DiagnosticSink.NOOP.enabled()).isFalse();
        assertThatCode(() -> DiagnosticSink.NOOP.emit("{}")).doesNotThrowAnyException();
    }
}
