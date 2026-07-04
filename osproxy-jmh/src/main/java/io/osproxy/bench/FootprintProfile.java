package io.osproxy.bench;

/**
 * A memory-footprint measurement: idle RSS right after startup versus RSS
 * after a soak of sustained traffic. The leak guard passes on
 * <b>either</b> a bounded growth ratio <b>or</b> a bounded absolute growth
 * — ratio alone false-positives when idle RSS is tiny (a few MiB growth
 * looks like "100% larger" on an 11 MiB idle footprint, which is noise,
 * not a leak).
 *
 * @param idleRssBytes RSS shortly after the process is ready
 * @param soakRssBytes RSS after the soak workload
 */
public record FootprintProfile(long idleRssBytes, long soakRssBytes) {

    /** {@code soak / idle}; 1.0 = no growth. */
    public double growthRatio() {
        return idleRssBytes == 0 ? Double.POSITIVE_INFINITY : (double) soakRssBytes / idleRssBytes;
    }

    /** {@code soak - idle}, in bytes (never negative in a real measurement). */
    public long growthBytes() {
        return soakRssBytes - idleRssBytes;
    }

    /**
     * Passes if growth stays under {@code maxRatio} OR under
     * {@code maxAbsoluteBytes} — either bound alone is enough.
     */
    public boolean judge(double maxRatio, long maxAbsoluteBytes) {
        return growthRatio() <= maxRatio || growthBytes() <= maxAbsoluteBytes;
    }

    /** One human-readable line, MiB with one decimal. */
    public String render() {
        return String.format(
                "idle=%.1fMiB soak=%.1fMiB growth=%.1fMiB (%.2fx)",
                idleRssBytes / 1048576.0, soakRssBytes / 1048576.0,
                growthBytes() / 1048576.0, growthRatio());
    }
}
