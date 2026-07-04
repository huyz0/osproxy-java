package io.osproxy.core;

import java.util.Optional;

/**
 * W3C trace-context: parsed from an incoming {@code traceparent}, or minted
 * fresh; always forwarded upstream so a request is one causal chain across
 * client → proxy → cluster. Pure parsing/formatting — no I/O, no RNG here
 * (fresh ids take caller-supplied randomness).
 *
 * @param traceId 32 hex chars
 * @param spanId 16 hex chars (this hop)
 * @param sampled the sampled flag from the incoming flags byte
 */
public record TraceContext(String traceId, String spanId, boolean sampled) {

    public TraceContext {
        if (!traceId.matches("[0-9a-f]{32}") || traceId.equals("0".repeat(32))) {
            throw new IllegalArgumentException("trace id must be 32 lowercase hex, non-zero");
        }
        if (!spanId.matches("[0-9a-f]{16}") || spanId.equals("0".repeat(16))) {
            throw new IllegalArgumentException("span id must be 16 lowercase hex, non-zero");
        }
    }

    /** Parses a {@code traceparent} header; empty when malformed. */
    public static Optional<TraceContext> parse(String traceparent) {
        if (traceparent == null) {
            return Optional.empty();
        }
        String[] parts = traceparent.strip().split("-");
        if (parts.length != 4 || !parts[0].equals("00")) {
            return Optional.empty();
        }
        try {
            boolean sampled = (Integer.parseInt(parts[3], 16) & 1) == 1;
            return Optional.of(new TraceContext(parts[1], parts[2], sampled));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Mints a fresh context from caller-supplied random bytes. */
    public static TraceContext mint(byte[] sixteenBytes, byte[] eightBytes) {
        return new TraceContext(hex(sixteenBytes, 16), hex(eightBytes, 8), true);
    }

    /** A child hop: same trace, new span id. */
    public TraceContext child(byte[] eightBytes) {
        return new TraceContext(traceId, hex(eightBytes, 8), sampled);
    }

    /** The {@code traceparent} header value for the upstream call. */
    public String toTraceparent() {
        return "00-" + traceId + "-" + spanId + (sampled ? "-01" : "-00");
    }

    private static String hex(byte[] bytes, int expected) {
        if (bytes == null || bytes.length != expected) {
            throw new IllegalArgumentException("need exactly " + expected + " random bytes");
        }
        StringBuilder out = new StringBuilder(expected * 2);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16))
                    .append(Character.forDigit(b & 0xF, 16));
        }
        // All-zero ids are invalid per the spec; nudge the last nibble.
        if (out.chars().allMatch(c -> c == '0')) {
            out.setCharAt(out.length() - 1, '1');
        }
        return out.toString();
    }
}
