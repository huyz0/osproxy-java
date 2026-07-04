package io.osproxy.observe;

/**
 * Receives directive-selected diagnostic documents (each a {@code /debug/explain}
 * doc) for delivery to a fleet-wide store/aggregator, keyed by the {@code
 * trace_id} the doc carries.
 *
 * <p>A proxy fleet serves a request on whichever instance the load balancer
 * picked, so a captured explain document lands on that instance; its local
 * break-glass ring and explain lookup are invisible to the others. This seam is
 * the fleet-coherent counterpart of the break-glass ring: the same {@code
 * ring_buffer} directive that fills the local tape also hands the shape-only
 * explain doc to a {@code DiagnosticSink}. The doc is shape-only by
 * construction (it is the explain doc), so the sink never carries a tenant
 * value.
 *
 * <p>Implementations must not block: {@code emit} is called inline on the
 * request path (only for captured requests), so any network I/O belongs on a
 * background thread. They must not throw. Delivery is best-effort; a slow or
 * down sink must never affect the request.
 */
public interface DiagnosticSink {

    /**
     * Whether this sink will do anything. The caller checks this before
     * building a document, so a disabled sink costs only this call even when
     * a capture directive is active.
     */
    default boolean enabled() {
        return true;
    }

    /** Hands off one shape-only diagnostic document (as JSON) for background delivery. */
    void emit(String docJson);

    /**
     * The default sink: off-instance emission is disabled, so a
     * directive-selected capture stays in the local break-glass ring only
     * (single-instance).
     */
    DiagnosticSink NOOP = new DiagnosticSink() {
        @Override
        public boolean enabled() {
            return false;
        }

        @Override
        public void emit(String docJson) {
            // no-op
        }
    };
}
