package io.osproxy.observe;

import java.util.concurrent.atomic.LongAdder;

/**
 * Per-instance request counters, served on the one introspection surface
 * that stays on in production. Shape-only: counts and statuses, never
 * tenant data. Fleet rollup is an external aggregator's job.
 */
public final class Metrics {

    private final LongAdder total = new LongAdder();
    private final LongAdder ok = new LongAdder();
    private final LongAdder clientError = new LongAdder();
    private final LongAdder upstreamError = new LongAdder();

    /** Tallies one completed request by its response status. */
    public void record(int status) {
        total.increment();
        if (status < 400) {
            ok.increment();
        } else if (status < 500) {
            clientError.increment();
        } else {
            upstreamError.increment();
        }
    }

    /** The snapshot as a stable JSON object. */
    public String toJson() {
        return "{\"requests_total\":" + total.sum()
                + ",\"requests_ok\":" + ok.sum()
                + ",\"requests_client_error\":" + clientError.sum()
                + ",\"requests_upstream_error\":" + upstreamError.sum() + "}";
    }
}
