package io.osproxy.kafka;

import io.osproxy.capture.AckProducer;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;

/**
 * The real {@link AckProducer} over the Apache Kafka client, configured for
 * the durability an honest 202 requires: {@code acks=all} (every in-sync
 * replica has the record), idempotence on (no duplicates from retries), and
 * {@code produceAcked} blocks until the broker's acknowledgment or throws.
 */
public final class KafkaAckProducer implements AckProducer, AutoCloseable {

    private static final long DEFAULT_ACK_TIMEOUT_SECONDS = 10;

    private final Producer<byte[], byte[]> producer;
    private final long ackTimeoutSeconds;

    /** Connects to the given bootstrap servers. */
    public KafkaAckProducer(String bootstrapServers) {
        Properties props = new Properties();
        props.putAll(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                // The 202 contract: acknowledged means durably accepted.
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true",
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "10000",
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "5000",
                ProducerConfig.LINGER_MS_CONFIG, "0"));
        this.producer = new KafkaProducer<>(
                props, new ByteArraySerializer(), new ByteArraySerializer());
        this.ackTimeoutSeconds = DEFAULT_ACK_TIMEOUT_SECONDS;
    }

    /**
     * Wraps an existing client (tests inject Kafka's MockProducer here).
     * Package-private: the broker client's types never leak into the public
     * surface, so dependents compile without kafka-clients.
     */
    KafkaAckProducer(Producer<byte[], byte[]> producer) {
        this(producer, DEFAULT_ACK_TIMEOUT_SECONDS);
    }

    /** Wraps an existing client with an explicit ack timeout (tests). */
    KafkaAckProducer(Producer<byte[], byte[]> producer, long ackTimeoutSeconds) {
        this.producer = producer;
        this.ackTimeoutSeconds = ackTimeoutSeconds;
    }

    @Override
    public void produceAcked(String topic, byte[] key, byte[] value) throws ProduceException {
        try {
            producer.send(new ProducerRecord<>(topic, key, value))
                    .get(ackTimeoutSeconds, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new ProduceException("broker refused the record", e.getCause());
        } catch (TimeoutException e) {
            throw new ProduceException("broker did not acknowledge in time", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProduceException("interrupted awaiting the acknowledgment", e);
        }
    }

    @Override
    public void close() {
        producer.close();
    }
}
