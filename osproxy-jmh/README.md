# osproxy-jmh

Wall-clock and allocation microbenchmarks (JMH, `-prof gc` for
allocations/op) over the hot transforms in `osproxy-rewrite`/
`osproxy-engine`, dimensioned across doc size, bulk size, and thread count.
Also hosts the perf-report vocabulary (`LatencySummary`, `PerfProfile`,
`FootprintProfile`) the Docker-backed e2e perf/soak tests in
`osproxy-server` use to render their results.

Never a CI gate: wall-clock numbers are host-specific, so nothing here
runs as part of `./gradlew check`. Run it locally when you want to measure
something.

## Depends on

- `osproxy-rewrite`, `osproxy-engine`, `osproxy-tenancy`, `osproxy-spi`,
  `osproxy-core`, `osproxy-sink` (JMH sourceset only, not part of the main
  dependency graph)

## Key types

- `LatencySummary`: nearest-rank percentiles from a batch of nanosecond
  samples.
- `PerfProfile`: proxy-vs-baseline comparison at one concurrency level
  (added p50/p99, throughput ratio).
- `FootprintProfile`: idle-vs-soak RSS comparison with the either/or
  (ratio OR absolute-bytes) leak guard.

Run: `./gradlew :osproxy-jmh:jmh -Pjmh.includes=<pattern>`. See
[Performance](../site/src/content/docs/11-performance.md).
