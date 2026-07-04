package io.osproxy.server;

import io.osproxy.core.Clock;
import io.osproxy.core.SystemClock;
import io.osproxy.observe.BreakGlassBuffer;
import io.osproxy.observe.DiagLevel;
import io.osproxy.observe.Directive;
import io.osproxy.observe.DiagnosticSink;
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
    private final BreakGlassBuffer breakGlass = new BreakGlassBuffer(256);
    private final Optional<PrintStream> requestLog;
    private final DirectiveSet.Store directives;
    private final Clock clock;
    private io.osproxy.observe.SpanExporter exporter = io.osproxy.observe.SpanExporter.NOOP;
    private DiagnosticSink diagnosticSink = DiagnosticSink.NOOP;

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

    /**
     * Enables off-instance delivery of directive-selected captures (default:
     * {@link DiagnosticSink#NOOP}, local break-glass ring only).
     */
    public Observability withDiagnosticSink(DiagnosticSink diagnosticSink) {
        this.diagnosticSink = diagnosticSink;
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
        long now = clock.monotonicNanos();
        DirectiveSet snapshot = directives.load();
        DiagLevel level = snapshot.evaluate(attrs, doc.requestId(), now);
        if (level.ordinal() >= DiagLevel.SHAPE.ordinal()) {
            explainStore.record(doc);
        }
        if (level == DiagLevel.VERBOSE) {
            requestLog.ifPresent(out -> out.println(doc.toJson()));
        }
        // Break-glass: capture the explanation when a ring_buffer directive
        // selected this request. Off by default, so this stays empty until an
        // operator flips it on. The doc is built once and both retained in the
        // local ring and pushed to the fleet diagnostic sink (keyed by
        // trace_id, already on the doc), so it is reachable on any instance.
        if (snapshot.wantsRingBuffer(attrs, doc.requestId(), now)) {
            String json = doc.toJson();
            if (diagnosticSink.enabled()) {
                // The seam's contract (DiagnosticSink) says implementations
                // must not throw, but this is the enforcement point, not just
                // the request: a sink that throws anyway (a broken pipe, a
                // synchronous network call gone wrong) must never break the
                // request it happened to observe. Best-effort really means
                // best-effort here, not "as long as nothing goes wrong".
                try {
                    diagnosticSink.emit(json);
                } catch (RuntimeException e) {
                    // Swallowed by design: diagnostics is never allowed to
                    // affect the request it is observing.
                }
            }
            breakGlass.capture(json);
        }
    }

    public Metrics metrics() {
        return metrics;
    }

    public ExplainStore explainStore() {
        return explainStore;
    }

    /** The break-glass tape, the explanations captured while a ring_buffer
     * directive applied. */
    public BreakGlassBuffer breakGlass() {
        return breakGlass;
    }
}
