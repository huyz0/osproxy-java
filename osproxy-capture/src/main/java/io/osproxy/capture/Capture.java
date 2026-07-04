package io.osproxy.capture;

import java.util.List;
import java.util.Map;

/**
 * The traffic-capture seam: full-fidelity request/response records for
 * replay and diagnosis, pushed to wherever the implementation sends them.
 * Capture must never fail a request — implementations swallow their own
 * errors.
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
}
