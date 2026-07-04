package io.osproxy.server;

import io.osproxy.observe.ExplainDoc;
import io.osproxy.observe.ExplainStore;
import io.osproxy.observe.Metrics;
import java.io.PrintStream;
import java.util.Optional;

/**
 * The server's observability bundle: metrics, the explain tape, and the
 * optional one-line-JSON request log. Everything recorded is shape-only.
 */
public final class Observability {

    private final Metrics metrics = new Metrics();
    private final ExplainStore explainStore;
    private final Optional<PrintStream> requestLog;

    public Observability(int explainCapacity, Optional<PrintStream> requestLog) {
        this.explainStore = new ExplainStore(explainCapacity);
        this.requestLog = requestLog;
    }

    /** Records one completed request everywhere it belongs. */
    public void record(ExplainDoc doc) {
        metrics.record(doc.status());
        explainStore.record(doc);
        requestLog.ifPresent(out -> out.println(doc.toJson()));
    }

    public Metrics metrics() {
        return metrics;
    }

    public ExplainStore explainStore() {
        return explainStore;
    }
}
