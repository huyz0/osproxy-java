package io.osproxy.server;

import io.osproxy.observe.DiagnosticSink;

/**
 * A {@link DiagnosticSink} that writes each directive-selected capture as one
 * tagged JSON line to stdout, the fleet-coherent counterpart of the local
 * break-glass ring. The platform's log collector scrapes it, so an aggregator
 * can serve the capture by the {@code trace_id} the explain doc carries, on
 * any instance. Tagged {@code "kind":"diagnostic_capture"} so it is
 * distinguishable from a request log line.
 */
public final class StdoutDiagnosticSink implements DiagnosticSink {

    @Override
    public void emit(String docJson) {
        System.out.println("{\"kind\":\"diagnostic_capture\",\"capture\":" + docJson + "}");
    }
}
