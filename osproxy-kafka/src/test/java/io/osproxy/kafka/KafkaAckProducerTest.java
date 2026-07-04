package io.osproxy.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.osproxy.capture.AckProducer;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.Test;

class KafkaAckProducerTest {

    @Test
    void producesAndReturnsAfterTheAck() throws Exception {
        // autoComplete: every send is acknowledged immediately.
        var mock = new MockProducer<byte[], byte[]>(
                true, null, new ByteArraySerializer(), new ByteArraySerializer());
        try (var producer = new KafkaAckProducer(mock)) {
            producer.produceAcked(
                    "writes",
                    "acme:7".getBytes(StandardCharsets.UTF_8),
                    "{\"op\":\"index\"}".getBytes(StandardCharsets.UTF_8));
        }
        assertThat(mock.history()).hasSize(1);
        assertThat(mock.history().get(0).topic()).isEqualTo("writes");
        assertThat(new String(mock.history().get(0).key())).isEqualTo("acme:7");
    }

    @Test
    void aBrokerErrorSurfacesAsProduceException() {
        var mock = new MockProducer<byte[], byte[]>(
                false, null, new ByteArraySerializer(), new ByteArraySerializer());
        try (var producer = new KafkaAckProducer(mock)) {
            var pending = new Thread(() -> {
                try {
                    producer.produceAcked("writes", new byte[] {1}, new byte[] {2});
                    throw new AssertionError("expected ProduceException");
                } catch (AckProducer.ProduceException expected) {
                    // the refusal propagates
                }
            });
            pending.start();
            // Let the send land, then fail it from the broker side.
            org.awaitility.Awaitility.await()
                    .atMost(java.time.Duration.ofSeconds(5))
                    .until(() -> mock.history().size() == 1);
            mock.errorNext(new org.apache.kafka.common.errors.NotEnoughReplicasException(
                    "not enough in-sync replicas"));
            try {
                pending.join(5_000);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
            assertThat(pending.isAlive()).isFalse();
        }
    }

    @Test
    void anUnacknowledgedSendTimesOutInsteadOfHangingForever() {
        var mock = new MockProducer<byte[], byte[]>(
                false, null, new ByteArraySerializer(), new ByteArraySerializer());
        // Nothing ever completes the send: produceAcked must give up (1s here).
        try (var producer = new KafkaAckProducer(mock, 1)) {
            assertThatThrownBy(() -> producer.produceAcked("writes", new byte[] {1}, new byte[] {2}))
                    .isInstanceOf(AckProducer.ProduceException.class)
                    .hasMessageContaining("did not acknowledge");
        }
    }

    @Test
    void theConnectingConstructorBuildsWithoutDialing() {
        // KafkaProducer connects lazily: constructing against a dead address
        // must succeed (the failure surfaces per-produce, as a ProduceException).
        try (var producer = new KafkaAckProducer("localhost:1")) {
            assertThat(producer).isNotNull();
        }
    }

    @Test
    void anInterruptSurfacesAndRestoresTheFlag() throws Exception {
        var mock = new MockProducer<byte[], byte[]>(
                false, null, new ByteArraySerializer(), new ByteArraySerializer());
        var producer = new KafkaAckProducer(mock, 30);
        var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        var thread = new Thread(() -> {
            try {
                producer.produceAcked("writes", new byte[] {1}, new byte[] {2});
            } catch (AckProducer.ProduceException e) {
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        thread.start();
        org.awaitility.Awaitility.await()
                .atMost(java.time.Duration.ofSeconds(5))
                .until(() -> mock.history().size() == 1);
        thread.interrupt();
        thread.join(5_000);
        assertThat(interrupted).isTrue();
        producer.close();
    }
}
