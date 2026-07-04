package io.osproxy.observe;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A bounded ring of the most recent {@link ExplainDoc}s, addressable by
 * request id — the break-glass tape an operator (or an external agent)
 * reads to answer "what happened to request X" without log access. Memory
 * is bounded by construction; the oldest entry falls off.
 */
public final class ExplainStore {

    private final int capacity;
    private final ArrayDeque<String> order = new ArrayDeque<>();
    private final Map<String, ExplainDoc> byId = new HashMap<>();

    public ExplainStore(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1");
        }
        this.capacity = capacity;
    }

    /** Records one completed request, evicting the oldest past capacity. */
    public synchronized void record(ExplainDoc doc) {
        if (byId.put(doc.requestId(), doc) == null) {
            order.addLast(doc.requestId());
            if (order.size() > capacity) {
                byId.remove(order.removeFirst());
            }
        }
    }

    /** Looks one request up by its echoed id. */
    public synchronized Optional<ExplainDoc> lookup(String requestId) {
        return Optional.ofNullable(byId.get(requestId));
    }

    /** The tape as a JSON array, oldest first. */
    public synchronized String toJsonArray() {
        StringBuilder out = new StringBuilder("[");
        boolean first = true;
        for (String id : order) {
            if (!first) {
                out.append(',');
            }
            out.append(byId.get(id).toJson());
            first = false;
        }
        return out.append(']').toString();
    }
}
