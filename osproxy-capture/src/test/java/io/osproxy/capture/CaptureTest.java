package io.osproxy.capture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CaptureTest {

    private static Capture.Record record() {
        return new Capture.Record(
                "PUT", "/orders/_doc/1",
                List.of(Map.entry("Authorization", "Bearer secret-token"),
                        Map.entry("x-tenant", "acme")),
                "{\"m\":1}".getBytes(), 201, "{}".getBytes());
    }

    @Test
    void memoryCaptureKeepsArrivalOrder() {
        var capture = new MemoryCapture();
        capture.capture(record());
        capture.capture(record());
        assertThat(capture.records()).hasSize(2);
        assertThat(capture.records().get(0).path()).isEqualTo("/orders/_doc/1");
    }

    @Test
    void redactingCaptureStripsCredentialsAndNothingElse() {
        var inner = new MemoryCapture();
        Capture.redacting(inner).capture(record());
        var captured = inner.records().get(0);
        assertThat(captured.headers()).contains(Map.entry("Authorization", "<redacted>"));
        assertThat(captured.headers()).contains(Map.entry("x-tenant", "acme"));
        assertThat(new String(captured.requestBody())).isEqualTo("{\"m\":1}");
    }

    @Test
    void safeCaptureSwallowsARuntimeExceptionFromTheDelegate() {
        Capture broken = record -> {
            throw new IllegalStateException("backend unavailable");
        };
        // The whole point: this must not throw, so a broken capture backend
        // can never be the reason a client-facing request fails.
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> Capture.safe(broken).capture(record()));
    }

    @Test
    void safeCaptureStillDelegatesOnSuccess() {
        var inner = new MemoryCapture();
        Capture.safe(inner).capture(record());
        assertThat(inner.records()).hasSize(1);
    }

    @Test
    void memoryProducerAcksAndFailsOnDemand() throws Exception {
        var producer = new MemoryAckProducer();
        producer.produceAcked("writes", "k".getBytes(), "v".getBytes());
        assertThat(producer.produced()).hasSize(1);
        assertThat(producer.produced().get(0).topic()).isEqualTo("writes");

        producer.setFailing(true);
        assertThatThrownBy(() -> producer.produceAcked("writes", "k".getBytes(), "v".getBytes()))
                .isInstanceOf(AckProducer.ProduceException.class);
        producer.setFailing(false);
        producer.produceAcked("writes", "k2".getBytes(), "v2".getBytes());
        assertThat(producer.produced()).hasSize(2);
    }
}
