package io.osproxy.capture;

/**
 * The acknowledged queue-producer seam: {@code produceAcked} returns only
 * after the record is durably accepted by the broker — the guarantee that
 * lets an async write honestly answer 202. A real Kafka client implements
 * this in the deployer's artifact; nothing here links a broker.
 */
public interface AckProducer {

    /** The broker refused or could not durably accept the record. */
    final class ProduceException extends Exception {
        public ProduceException(String message, Throwable cause) {
            super(message, cause);
        }

        public ProduceException(String message) {
            this(message, null);
        }
    }

    /** Produces one record and blocks until it is durably acknowledged. */
    void produceAcked(String topic, byte[] key, byte[] value) throws ProduceException;
}
