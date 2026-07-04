package io.osproxy.sink;

import io.osproxy.core.Epoch;
import io.osproxy.core.Target;
import java.util.List;

/**
 * The write vocabulary crossing the sink seam: a batch of epoch-stamped
 * operations, each with its own target (a bulk request's documents may route
 * to different placements), acknowledged per-op in order.
 */
public final class WriteBatch {

    private WriteBatch() {}

    /** One routed, epoch-stamped operation. */
    public record Op(Target target, DocOp op, Epoch epoch) {}

    /** The per-op outcome, in request order. */
    public record OpResult(int status, String result, String physicalId) {
        /** Whether the upstream accepted the operation. */
        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    /** The batch acknowledgment: one result per op, order preserved. */
    public record Ack(List<OpResult> results) {
        public Ack {
            results = List.copyOf(results);
        }
    }
}
