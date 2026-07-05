package io.osproxy.sink;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * A transform the engine applies while the sink is already mid-upload:
 * {@code writeStreaming}/{@code searchStreaming} invoke this directly inside
 * the upstream client's output-stream callback, on the same thread that's
 * already writing the request — no pipe, no second thread. Kept ignorant of
 * {@code osproxy-rewrite} on purpose (this module doesn't depend on it); the
 * engine supplies the transform as a closure over whichever rewrite function
 * applies (field injection, query wrapping, or a plain verbatim copy).
 */
@FunctionalInterface
public interface StreamTransform {
    /** Reads {@code in} to exhaustion, writing the transformed result to {@code out}. */
    void apply(InputStream in, OutputStream out) throws IOException;

    /** The identity transform: copies {@code in} to {@code out} untouched. */
    static StreamTransform verbatim() {
        return (in, out) -> in.transferTo(out);
    }
}
