package io.osproxy.sink;

import java.util.List;

/**
 * The write destination seam (ADR-008 in the Rust project): the engine
 * builds fully-transformed batches, the sink commits them. Blocking calls —
 * the server runs them on virtual threads.
 */
public interface Sink {
    /** Commits the batch, acknowledging each op in order. */
    WriteBatch.Ack write(List<WriteBatch.Op> ops) throws SinkException;
}
