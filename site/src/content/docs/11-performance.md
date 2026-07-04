---
title: "11. Performance"
---
Every number on this page came from a measurement in this repository, run
on this developer's box, not an aspirational target. Reproduce them with
the commands in each section.

## Test environments

- **JMH microbenchmarks** (`osproxy-jmh`): in-process, JIT-warmed, `gc`
  profiler for allocations/op. No network, no container.
- **E2e perf harness** (`osproxy-server`'s `PerfHarnessE2eTest`,
  `@Tag("integration")`): a real single-node OpenSearch 2.11 Testcontainer,
  direct-vs-proxied comparison, concurrency swept via virtual threads.
- **Footprint/soak** (`SoakE2eTest`): the real `osproxy-server` binary
  spawned as its own OS process (not in-JVM), RSS read from
  `/proc/<pid>/statm`, forced GC before each snapshot so the number reflects
  the live set, not uncollected garbage.
- **Controlled Rust-vs-Java comparison**: both binaries run sequentially
  (not concurrently) against the *same* OpenSearch container on the same
  box, same Python/`urllib` load generator, 3 repetitions per concurrency
  level.

## Load matrix: dimensional microbenchmarks

`DimensionalTransformBench` sweeps document size (256B / 4KiB / 64KiB) and
bulk-batch size (10 / 100 / 1000 docs, at 256B and 4KiB) independently, plus
an 8-thread contended variant of the hot parse/inject/wrap paths. Measured
inject+serialize cost scales from ~0.7µs at 2KiB up to ~139µs at 187KiB —
Jackson tree materialization is the marked optimization target for a future
streaming-twin pass, not yet done.

## Per-request added latency and throughput (e2e, real OpenSearch)

Ingest workload, direct-to-cluster baseline vs. through the proxy, swept
c=1/8/32/64 (1600 ops/level):

| Concurrency | Throughput (ops/s) | Ratio (proxied/baseline) | Added p50 | Added p99 |
|---|---|---|---|---|
| 1 | ~91 | 0.93–1.03 | ~0 (sampling noise) | -1.4ms to +0.8ms |
| 8 | — | 0.93–1.03 | ~0 | — |
| 32 | — | 0.93–1.03 | ~0 | — |
| 64 | ~3596 | 0.93–1.03 | ~0 | up to +13.3ms |

Throughput scales 91 → 3596 ops/s from c=1 to c=64 (proxy scales by pool
reuse, not by serializing on a lock). The c=64 p99 tail is worth reading
honestly: it may be a real GC pause or JIT effect, or it may be thin
sampling at high concurrency (1600 ops isn't much for a tail percentile) —
not yet isolated.

Reproduce: `./gradlew :osproxy-server:test -PincludeIntegration --tests PerfHarnessE2eTest`

## Memory footprint under sustained load

20,000 sequential requests, 256MiB-capped heap, forced GC before each RSS
snapshot:

| | RSS |
|---|---|
| Idle | ~116 MiB |
| After soak | ~231 MiB (≈2.0x, ~115 MiB absolute growth) |

Bounded, not leaking — RSS stayed flat across repeated runs at this ratio.
The idle number itself (~116 MiB) is the interesting one for comparison
purposes: see below.

Reproduce: `./gradlew :osproxy-server:test -PincludeIntegration --tests SoakE2eTest`

## Rust vs Java, measured side by side

Same OpenSearch container, same box, both binaries run one after another
(not concurrently), same load generator, 3 reps per concurrency level,
single-doc POST ingest:

| Concurrency | Rust ops/s (3 reps) | Java ops/s (3 reps) | Gap |
|---|---|---|---|
| 1 | 90.2, 85.5, 90.7 | 88.3, 91.9, 92.1 | <4% |
| 16 | 1020, 1026, 1043 | 1063, 1048, 1066 | <4% |
| 64 | 2098, 2169, 2202 | 2083, 2092, 2044 | <4% |

**A dead heat** at this workload — every gap is smaller than the
rep-to-rep spread of a single binary. OpenSearch's own write latency
dominates both; the proxy layer itself isn't the bottleneck for either
implementation here, so language/runtime differences don't show up in
throughput.

**Memory footprint is the one real, structural gap**: Rust idles at ~11
MiB RSS; Java idles at ~116 MiB. That's the JVM baseline (class metadata,
JIT, GC bookkeeping), not proxy logic — expected and not something a code
change closes. If footprint is your binding constraint (high pod density,
serverless cold starts), that's the number to weigh; if it's throughput or
p50/p99 latency under this kind of workload, the two are presently
indistinguishable.

This comparison is a single controlled run, not a statistically
rigorous study — read it as "same order of magnitude, footprint aside,"
not as a claim of parity down to the percentage point.

## Connection handling

`OpenSearchSink` pools one Helidon `WebClient` per cluster base URL, with a
circuit breaker (`CircuitBreaker`, CAS-based half-open probing) that opens
after 5 consecutive failures and stays open 5 seconds by default. There is
no per-request connection setup on the steady-state path.

## Reproduce everything

```sh
./gradlew check                                          # unit + JaCoCo + ArchUnit, no Docker
./gradlew jmh                                             # microbenchmarks
./gradlew :osproxy-server:test -PincludeIntegration       # e2e + perf + soak, needs Docker
```
