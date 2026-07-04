package io.osproxy.server;

import io.osproxy.core.Clock;
import io.osproxy.core.SystemClock;
import io.osproxy.observe.DiagLevel;
import io.osproxy.observe.Directive;
import io.osproxy.observe.DirectiveSet;
import io.osproxy.observe.ExplainDoc;
import io.osproxy.observe.ExplainStore;
import io.osproxy.observe.Metrics;
import java.io.PrintStream;
import java.util.Optional;

/**
 * The server's observability bundle: metrics, the explain tape, and the
 * optional one-line-JSON request log — recording gated per request by the
 * directive plane. Metrics always tick; the explain capture needs SHAPE;
 * the log line needs VERBOSE. Everything recorded is shape-only.
 */
public final class Observability {

    private final Metrics metrics = new Metrics();
    private final ExplainStore explainStore;
    private final Optional<PrintStream> requestLog;
    private final DirectiveSet.Store directives;
    private final Clock clock;
    private io.osproxy.observe.SpanExporter exporter = io.osproxy.observe.SpanExporter.NOOP;

    public Observability(int explainCapacity, Optional<PrintStream> requestLog) {
        this(explainCapacity, requestLog,
                new DirectiveSet.InMemoryStore(DirectiveSet.baseline(
                        requestLog.isPresent() ? DiagLevel.VERBOSE : DiagLevel.SHAPE)),
                new SystemClock());
    }

    public Observability(
            int explainCapacity, Optional<PrintStream> requestLog,
            DirectiveSet.Store directives, Clock clock) {
        this.explainStore = new ExplainStore(explainCapacity);
        this.requestLog = requestLog;
        this.directives = directives;
        this.clock = clock;
    }

    /** Enables span export (default: off, all export cost skipped). */
    public Observability withExporter(io.osproxy.observe.SpanExporter exporter) {
        this.exporter = exporter;
        return this;
    }

    /** The exporter (the handler feeds it when enabled). */
    public io.osproxy.observe.SpanExporter exporter() {
        return exporter;
    }

    /** The directive store (the admin endpoint publishes into it). */
    public DirectiveSet.Store directives() {
        return directives;
    }

    public Clock clock() {
        return clock;
    }

    /** Records one completed request at the directive-effective level. */
    public void record(ExplainDoc doc, Directive.RequestAttrs attrs) {
        metrics.record(doc.status());
        DiagLevel level = directives.load()
                .evaluate(attrs, doc.requestId(), clock.monotonicNanos());
        if (level.ordinal() >= DiagLevel.SHAPE.ordinal()) {
            explainStore.record(doc);
        }
        if (level == DiagLevel.VERBOSE) {
            requestLog.ifPresent(out -> out.println(doc.toJson()));
        }
    }

    public Metrics metrics() {
        return metrics;
    }

    public ExplainStore explainStore() {
        return explainStore;
    }
}
