package io.osproxy.observe;

/**
 * How much a request's completion is recorded. Ordered: a higher level
 * implies everything below it. All levels are shape-only — the levels widen
 * *where* the shape goes, never *what* it contains.
 */
public enum DiagLevel {
    /** Metrics only; no explain capture, no log line. */
    OFF("off"),
    /** Metrics + the explain store (the default baseline). */
    SHAPE("shape"),
    /** Metrics + explain + a JSON log line (when a log sink is wired). */
    VERBOSE("verbose");

    private final String wireName;

    DiagLevel(String wireName) {
        this.wireName = wireName;
    }

    /** Stable wire vocabulary. */
    public String wireName() {
        return wireName;
    }

    /** Parses the wire name; null for unknown (caller decides the failure). */
    public static DiagLevel fromWireName(String name) {
        for (DiagLevel level : values()) {
            if (level.wireName.equals(name)) {
                return level;
            }
        }
        return null;
    }
}
