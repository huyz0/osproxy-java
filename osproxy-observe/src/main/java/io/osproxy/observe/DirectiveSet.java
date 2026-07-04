package io.osproxy.observe;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The active directives, evaluated per request: the effective level is the
 * baseline unless a matching, unexpired, sampled-in directive says otherwise
 * — highest level wins (a silencing {@code OFF} directive only wins when
 * nothing raises the level above it).
 */
public record DirectiveSet(DiagLevel baseline, List<Directive> directives) {

    public DirectiveSet {
        directives = List.copyOf(directives);
    }

    /** The empty set at a baseline. */
    public static DirectiveSet baseline(DiagLevel level) {
        return new DirectiveSet(level, List.of());
    }

    /** The effective level for one request. */
    public DiagLevel evaluate(Directive.RequestAttrs attrs, String requestId, long nowNanos) {
        DiagLevel effective = null;
        for (Directive directive : directives) {
            if (directive.matches(attrs, requestId, nowNanos)) {
                if (effective == null || directive.level().ordinal() > effective.ordinal()) {
                    effective = directive.level();
                }
            }
        }
        return effective != null ? effective : baseline;
    }

    /** Whether any matching, unexpired directive wants break-glass capture. */
    public boolean wantsRingBuffer(Directive.RequestAttrs attrs, String requestId, long nowNanos) {
        for (Directive directive : directives) {
            if (directive.ringBuffer() && directive.matches(attrs, requestId, nowNanos)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The store seam: the server polls {@code load()} per request, so a
     * fleet-wide publish takes effect without a restart. Implementations
     * must make {@code load} cheap (a volatile read, not I/O).
     */
    public interface Store {
        DirectiveSet load();
    }

    /** The reference in-memory store: publish replaces the whole set. */
    public static final class InMemoryStore implements Store {
        private final AtomicReference<DirectiveSet> current;

        public InMemoryStore(DirectiveSet initial) {
            this.current = new AtomicReference<>(initial);
        }

        @Override
        public DirectiveSet load() {
            return current.get();
        }

        /** Atomically replaces the active set. */
        public void publish(DirectiveSet next) {
            current.set(next);
        }
    }
}
