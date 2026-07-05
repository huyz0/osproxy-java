package io.osproxy.sink;

import io.osproxy.core.Target;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;

/**
 * The write destination seam (ADR-008 in the Rust project): the engine
 * builds fully-transformed batches, the sink commits them. Blocking calls —
 * the server runs them on virtual threads.
 */
public interface Sink {
    /** Commits the batch, acknowledging each op in order. */
    WriteBatch.Ack write(List<WriteBatch.Op> ops) throws SinkException;

    /**
     * Streaming twin of {@link #write} for a single index/create op whose
     * physical id and target are known without reading the body (index
     * CRUD's {@code {id}}-templated placements). {@code transform} runs
     * directly inside the upstream write's output-stream callback — reading
     * {@code requestBody} and writing the transformed document straight to
     * the upstream connection, on the same thread, no intermediate pipe or
     * second thread. Default: unsupported, so a sink that hasn't wired a
     * real streaming transport (or {@link MemorySink}, which has no
     * transport at all) fails closed rather than silently buffering behind
     * the caller's back.
     */
    default WriteBatch.OpResult writeStreaming(
            Target target, boolean create, String physicalId,
            InputStream requestBody, StreamTransform transform, Optional<String> routing)
            throws SinkException {
        throw new SinkException(
                io.osproxy.core.ErrorCode.UNSUPPORTED_ENDPOINT,
                "this sink does not support streaming writes");
    }
}
