package io.osproxy.spi;

/**
 * Where a partition's placement migration stands. A shape-only label (never
 * tenant data) so observability can show migration progress.
 */
public enum MigrationPhase {
    /** No migration in flight; writes admitted at the current epoch. */
    SETTLED,
    /**
     * A migration is announced: writes still land at the current epoch, but
     * the fleet is converging on the new placement (no new long-lived state
     * should be created against the old one).
     */
    DRAINING,
    /**
     * The placement is flipping: every write is refused (409) until the new
     * placement settles, so no write can land in the old location after the
     * copy point — the correctness heart of a live migration.
     */
    CUTOVER
}
