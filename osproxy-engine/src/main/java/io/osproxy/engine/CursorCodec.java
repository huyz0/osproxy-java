package io.osproxy.engine;

import java.util.Optional;

/**
 * Seals cluster affinity into cursor ids: a scroll/PIT id returned to a
 * client is wrapped with the cluster it lives on, so a continue routes to
 * the same cluster even if the partition's placement changes mid-cursor.
 * The wire form must be tamper-evident (the reference implementation signs
 * with HMAC) — a forged cluster name must not decode.
 */
public interface CursorCodec {

    /**
     * Wraps an upstream cursor id with its cluster affinity and the owning
     * partition — a cursor is not a bearer capability; another tenant
     * presenting a leaked envelope is refused, not served.
     */
    String encode(String cluster, String partition, String upstreamId);

    /** Unwraps a wire id; empty when the envelope is invalid or forged. */
    Optional<Decoded> decode(String wireId);

    /** The unwrapped affinity. */
    record Decoded(String cluster, String partition, String upstreamId) {}
}
