package io.osproxy.sink;

/**
 * Marks a failure that happened inside a {@link StreamTransform} — parsing
 * or validating the client's body — as distinct from a failure writing to
 * the upstream connection itself. Both surface as an {@code IOException}
 * (or a wrapper around one) by the time they reach the sink's caller, and
 * both can legitimately happen inside the same {@code outputStream}
 * callback; without a distinguishing marker there is no reliable way to
 * tell "the document was malformed" apart from "the connection died" once
 * the exception has propagated through Helidon's own wrapping. The
 * transform wraps its own failures in this type; the sink otherwise leaves
 * write-side exceptions unwrapped, so the engine can tell the two apart
 * from a {@link SinkException}'s cause chain.
 */
public final class TransformFailedException extends java.io.IOException {
    public TransformFailedException(Throwable cause) {
        super(cause);
    }
}
