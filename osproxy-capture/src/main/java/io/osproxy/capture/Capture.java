package io.osproxy.capture;

import java.util.List;
import java.util.Map;

/**
 * The traffic-capture seam: full-fidelity request/response records for
 * replay and diagnosis, pushed to wherever the implementation sends them.
 * Capture must never fail a request — implementations are documented to
 * swallow their own errors, but that's only a convention until every call
 * site composes through {@link #safe}, which enforces it regardless of
 * what a given implementation actually does.
 */
public interface Capture {

    /** One captured exchange. Bodies are raw; redaction is a wrapper's job. */
    record Record(
            String method,
            String path,
            List<Map.Entry<String, String>> headers,
            byte[] requestBody,
            int status,
            byte[] responseBody) {}

    /** Captures one exchange (best-effort, non-throwing). */
    void capture(Record record);

    /** A capture that strips credential-bearing headers before delegating. */
    static Capture redacting(Capture delegate) {
        return record -> delegate.capture(new Record(
                record.method(),
                record.path(),
                record.headers().stream()
                        .map(h -> h.getKey().equalsIgnoreCase("authorization")
                                ? Map.entry(h.getKey(), "<redacted>")
                                : h)
                        .toList(),
                record.requestBody(),
                record.status(),
                record.responseBody()));
    }

    /**
     * A capture that never lets {@code delegate} fail the request it's
     * capturing: any {@link RuntimeException} from {@code delegate.capture}
     * is dropped rather than propagated. Compose every call site through
     * this (as {@code AppHandler}'s choke point does) so the never-throw
     * contract holds regardless of what a particular {@code Capture}
     * implementation actually does — the doc comment alone doesn't enforce
     * it, this does.
     */
    static Capture safe(Capture delegate) {
        return record -> {
            try {
                delegate.capture(record);
            } catch (RuntimeException e) {
                // Best-effort by contract: a broken capture backend must
                // never be the reason a client-facing request fails.
            }
        };
    }
}
