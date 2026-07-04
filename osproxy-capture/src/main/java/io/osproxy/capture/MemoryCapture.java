package io.osproxy.capture;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/** The in-memory reference capture (tests, local diagnosis). */
public final class MemoryCapture implements Capture {

    private final ConcurrentLinkedQueue<Record> records = new ConcurrentLinkedQueue<>();

    @Override
    public void capture(Record record) {
        records.add(record);
    }

    /** The captured records in arrival order. */
    public List<Record> records() {
        return List.copyOf(records);
    }
}
