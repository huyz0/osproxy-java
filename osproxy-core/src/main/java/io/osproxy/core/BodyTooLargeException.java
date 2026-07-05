package io.osproxy.core;

/**
 * Marks a request body that exceeded the configured size cap. A shared
 * vocabulary type (not itself I/O) so a stream-level cap enforced by the
 * ingress (which owns the raw bytes) can be recognized by the engine (which
 * runs the streaming transform reading those bytes) without either module
 * depending on the other.
 */
public final class BodyTooLargeException extends java.io.IOException {
    public BodyTooLargeException(String message) {
        super(message);
    }
}
