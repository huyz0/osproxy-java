package io.osproxy.observe;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A bounded, in-order tape of recent explanations, captured only when a
 * directive asks ({@code ringBuffer=true}). Distinct from {@link ExplainStore},
 * which is the always-on, lookup-by-request-id store: this is a sequence an
 * operator turns on deliberately, when a class of request is failing and the
 * ids aren't known up front, flip a {@code ring_buffer} directive and read
 * back the last N matching requests as a forensic tape.
 *
 * <p>Single-instance by design (the captured tape lives on the instance that
 * handled the requests); bounded so it costs nothing until used and cannot
 * grow without limit once on.
 */
public final class BreakGlassBuffer {

    private final int capacity;
    private final Deque<String> entries = new ArrayDeque<>();

    public BreakGlassBuffer(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    /** Captures one explanation document (as JSON), evicting the oldest if full. */
    public synchronized void capture(String docJson) {
        if (entries.size() >= capacity) {
            entries.removeFirst();
        }
        entries.addLast(docJson);
    }

    /** A snapshot of the captured tape, oldest first, the break-glass read. */
    public synchronized List<String> snapshot() {
        return new ArrayList<>(entries);
    }

    /** How many captures the tape currently holds. */
    public synchronized int size() {
        return entries.size();
    }
}
