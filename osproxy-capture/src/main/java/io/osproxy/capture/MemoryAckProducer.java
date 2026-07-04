package io.osproxy.capture;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/** The in-memory reference producer: records everything, can be failed. */
public final class MemoryAckProducer implements AckProducer {

    /** One accepted record. */
    public record Produced(String topic, byte[] key, byte[] value) {}

    private final ConcurrentLinkedQueue<Produced> produced = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean failing = new AtomicBoolean();

    @Override
    public void produceAcked(String topic, byte[] key, byte[] value) throws ProduceException {
        if (failing.get()) {
            throw new ProduceException("broker unavailable (test)");
        }
        produced.add(new Produced(topic, key, value));
    }

    /** Makes every subsequent produce fail (or succeed again). */
    public void setFailing(boolean fail) {
        failing.set(fail);
    }

    /** Everything acknowledged so far, in order. */
    public List<Produced> produced() {
        return List.copyOf(produced);
    }
}
