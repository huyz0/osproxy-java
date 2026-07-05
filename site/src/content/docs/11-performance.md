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
c=1/8/32/64 (1600 ops/level). **Re-measured 2026-07-05, after streaming
ingest became the default path** for any tenancy where it's eligible
(`Pipeline.supportsStreamingIngest`, true for the reference tenancy) — this
table used to describe the pre-streaming buffered path exclusively; it now
reflects what actually runs:

| Concurrency | Baseline ops/s | Proxied ops/s | Ratio | Added p50 | Added p99 |
|---|---|---|---|---|---|
| 1 | 88 | 90 | 1.03 | -0.25ms | -1.59ms |
| 8 | 546 | 556 | 1.02 | -0.17ms | -1.09ms |
| 32 | 1974 | 2018 | 1.02 | +0.42ms | -4.81ms |
| 64 | 3976 | 3607 | 0.91 | +1.38ms | +4.84ms |

Compared to the last pre-streaming measurement: c=64 throughput ratio moved
from the 0.93–1.03 band down to 0.91 — a real, if modest, regression at high
concurrency, plausibly the per-request `PipedOutputStream` + virtual-thread
producer streaming ingest now spins up even for small documents (see
[Streaming transform cost](#streaming-transform-cost-jmh) below). The tail
latency actually improved (c=64 added p99 was up to +13.3ms before, now
+4.84ms), so this is a real trade, not a strict win or loss. One run each,
not a statistically rigorous sweep — reproduce before trusting the exact
numbers.

Reproduce: `./gradlew :osproxy-server:test -PincludeIntegration --tests PerfHarnessE2eTest`

## Memory footprint under sustained load

20,000 sequential requests, 256MiB-capped heap, forced GC before each RSS
snapshot. **Re-measured 2026-07-05** (same streaming-by-default caveat as
above):

| | RSS | Previous (pre-streaming) |
|---|---|---|
| Idle | 117.9 MiB | ~116 MiB |
| After soak | 281.4 MiB (2.39x, +163.5 MiB) | ~231 MiB (2.0x, +115 MiB) |

Idle is unchanged (streaming adds no fixed cost at rest). Soak growth is
measurably higher than the pre-streaming baseline — +163.5 MiB vs. +115
MiB, a genuine regression, consistent with a `PipedOutputStream`/
`PipedInputStream` pair plus a dedicated virtual thread being allocated per
eligible request instead of one contiguous buffer. Still bounded (RSS
plateaus, doesn't climb unboundedly), but the streaming architecture is not
free at this axis — this is new information the pre-streaming table didn't
have to account for.

Reproduce: `./gradlew :osproxy-server:test -PincludeIntegration --tests SoakE2eTest`

## Rust vs Java, measured side by side

**This comparison predates streaming ingest and has not been re-run since**
— read it as describing the pre-streaming buffered path, not necessarily
today's default. Given the e2e table above shows a real (if modest)
regression at c=64 post-streaming, this table is due a re-run before being
cited as current.

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

## Streaming passthrough bypasses the body cap entirely

`PassthroughE2eTest.aBodyLargerThanMaxBodyBytesStreamsThroughWithoutBeingCapped`
proxies a 16 MiB body through a proxy configured with a 1 KiB
`osproxy.max-body-bytes` cap and asserts the upstream receives all 16 MiB —
proving the streaming passthrough path (`AppHandler.streamPassthrough` →
`Reader.forwardStreaming`) genuinely never materializes the body as a
`byte[]`, rather than just raising an internal buffer size.

Tenanted `_bulk` gets the same proof despite still resolving tenancy and
running the per-item transform:
`BulkStreamingE2eTest.aTenantedBulkBodyLargerThanTheCapStreamsThroughAndDispatchesEveryItem`
sends a 2,000-item NDJSON bulk body (well over the same 1 KiB cap) through a
real `ReferenceTenancy` + `OpenSearchSink` pipeline and asserts every item
dispatches and comes back `201`. `AppHandler.streamBulk` →
`Pipeline.openBulkStream`/`MultiOps.bulkStreaming` parses and dispatches one
NDJSON item at a time, so the request is never buffered whole either —
`_bulk`'s existing line-oriented framing made it the second endpoint class to
close after passthrough.

Single-doc ingest closes the memory-buffering side of the same gap, but
deliberately keeps the cap: `Fields.injectFieldsStreaming` copies the
client's document token by token into a pipe that `OpenSearchSink.writeStreaming`
uploads directly, so a document up to `osproxy.max-body-bytes` costs one
streaming pass instead of a buffer copy plus a full Jackson tree — but a
document *over* the cap is still refused with `413`
(`IngressHardeningTest.overCapBodiesAreRefusedWith413` — pre-existing,
unmodified, still green), since that NFR predates streaming and streaming
doesn't get to silently drop it. Eligibility (`Pipeline.supportsStreamingIngest`)
depends on the physical target and id being derivable without reading the
body — true for the reference tenancy, false only for a body-derived
partition key.

Search and count close the same gap the same way:
`Queries.wrapQueryStreaming` streams the client's query into the
`bool`/`must`/`filter` enclosure token by token (`SearchStreamingE2eTest`
proves the wrapped shape lands on the wire correctly), except the one
`aggs`/`aggregations` clause, read as a tree because the unfilterable check
needs to see the whole subtree before deciding to refuse — proven
unaffected on the streaming path too
(`unfilterableSearchConstructsAreStillRefusedOnTheStreamingPath`). Search also
keeps enforcing `osproxy.max-body-bytes`
(`aSearchBodyLargerThanTheCapIsStillRefusedWith413`): a query is not the
kind of legitimately-large aggregate payload passthrough/`_bulk` exist to
escape, so the cap stays.

## Streaming transform cost (JMH)

The measurements above proved streaming *works* and preserves isolation.
They didn't establish what it *costs* — no benchmark existed for the
streaming transforms or the pipe/virtual-thread machinery
`ingestDocStreaming`/`searchStreaming` build per request. `StreamingTransformBench`
(2026-07-05) closes that gap, at two levels: the token-level transforms
alone, and the full `Pipeline` call as it actually runs in production
(`MemorySink`, no real network — isolates the engine-side cost only).

**Transform-only** (no pipe, no thread — parser/generator vs. tree),
average time per op:

| Transform | 256B | 4KiB | 64KiB |
|---|---|---|---|
| `injectFields` (buffered) | 0.70µs | 7.44µs | 143.9µs |
| `injectFieldsStreaming` | 0.64µs | 4.65µs | 86.7µs |
| `wrapQuery` (buffered) | 0.93µs | 6.87µs | 144.3µs |
| `wrapQueryStreaming` | 0.91µs | 4.90µs | 88.2µs |

At the transform level alone, streaming is faster in wall-clock time at
every size tested — roughly 40% faster by 64KiB — but allocates *more*
bytes per op (e.g. `injectFieldsStreaming` at 64KiB: 437KB/op vs.
`injectFields`'s 187KB/op). Jackson's streaming generator apparently
trades allocation for fewer full-tree passes; worth knowing, not
alarming in isolation.

`parseBulk` inverts this: the streaming line-by-line parse
(`Bulk.parseBulkStream`, drained fully here) is consistently *slower*
than the batch `String.split` buffered version, and allocates more —
at 100 docs × 4KiB, 550µs vs. 275µs (about 2x). `BufferedReader.readLine()`
plus per-line parser construction costs more than one shared parser over
a pre-split array. Not disqualifying (`_bulk` streaming's real value is
escaping `max-body-bytes`, not raw parse speed), but it means the parse
step itself isn't the free win the transform-only ingest/search numbers
might suggest.

**Full pipeline, including the pipe + virtual-thread producer** —
this is the number that matters for "is streaming ingest worth it":

| | 256B | 4KiB | 64KiB |
|---|---|---|---|
| `pipelineIngestBuffered` (`Pipeline.handle`) | 1.52µs | 11.55µs | 162.0µs |
| `pipelineIngestStreaming` (`Pipeline.ingestDocStreaming`) | 18.53µs | 24.72µs | 478.6µs |
| Ratio | **12.2x slower** | **2.1x slower** | **3.0x slower** |

Allocation tells the same story: 5.6KB/op buffered vs. 58.8KB/op streaming
at 256B (over 10x), narrowing to 557KB vs. 255KB (2.2x) at 64KiB.

**This is a real, measured cost, not noise** — streaming ingest is slower
and allocates more at *every* size tested, worst at the small end where
the `PipedOutputStream`/`PipedInputStream` pair and the dedicated virtual
thread `ingestDocStreaming` spins up per request dominate over whatever
buffering they're saving. It lines up with what the e2e re-run above
already hinted at (throughput ratio dipping to 0.91 at c=64, up from the
0.93–1.03 band pre-streaming) and the soak growth regression (+163.5 MiB
vs. +115 MiB) — all three independent measurements point the same
direction: the pipe/thread-hop architecture has a real cost, and for
typical single-document sizes it looks larger than the cost it was meant
to replace.

**What this means, plainly:** streaming ingest still does what it was
built for — a document over `max-body-bytes` still gets rejected
correctly, and one *within* the cap now takes a different, not
obviously cheaper, code path than before. The functional behavior (the
proxy stays correct, the cap still enforces) hasn't changed. The
performance claim implicit in shipping it as the default path for
eligible tenancies — "this should be at least as cheap as buffering,
since it avoids materializing a full copy" — is not supported by this
measurement for ordinary request sizes. Search streaming almost
certainly has the same shape (same pipe/thread pattern, same producer
close relationship) but wasn't separately benchmarked here — added as a
follow-up if this is prioritized. A lower-overhead approach worth
considering: a single-thread, no-pipe token copy (the same technique
`_bulk` streaming already uses, dispatching per item without ever
opening a `PipedOutputStream`) would likely close most of this gap
without giving up the memory-buffering benefit for large documents.

Reproduce: `./gradlew :osproxy-jmh:jmh -Pjmh.includes=Streaming`

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
