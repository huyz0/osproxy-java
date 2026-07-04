package io.osproxy.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.Test;

class StdoutDiagnosticSinkTest {

    @Test
    void emitsOneTaggedJsonLinePerCapture() {
        var sink = new StdoutDiagnosticSink();
        assertThat(sink.enabled()).isTrue();

        var captured = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(captured, true));
        try {
            sink.emit("{\"request_id\":\"r1\",\"trace_id\":\"abc\"}");
        } finally {
            System.setOut(original);
        }

        String line = captured.toString().strip();
        assertThat(line).contains("\"kind\":\"diagnostic_capture\"");
        assertThat(line).contains("\"capture\":{\"request_id\":\"r1\",\"trace_id\":\"abc\"}");
    }
}
