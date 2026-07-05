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
c=1/8/32/64 (1600 ops/level). **Re-measured 2026-07-05**, current as of the
fix described in [Streaming transform cost](#streaming-transform-cost-jmh)
below — streaming ingest is the default path for any tenancy where it's
eligible (`Pipeline.supportsStreamingIngest`, true for the reference
tenancy), so this reflects what actually runs:

| Concurrency | Baseline ops/s | Proxied ops/s | Ratio | Added p50 | Added p99 |
|---|---|---|---|---|---|
| 1 | 89 | 91 | 1.02 | -0.06ms | -3.28ms |
| 8 | 559 | 543 | 0.97 | +0.36ms | +0.03ms |
| 32 | 2122 | 2074 | 0.98 | +0.61ms | -0.94ms |
| 64 | 3936 | 3701 | 0.94 | +1.23ms | -0.43ms |

This table went through two measurements. The first (same day) caught a
real bug: streaming ingest's first implementation spun up a
`PipedOutputStream`/`PipedInputStream` pair plus a dedicated virtual
thread per request, purely to move bytes from a parser to a generator —
pure thread-hop overhead, since Helidon's `outputStream()` callback
already hands the destination stream to the calling thread synchronously
(no second thread was ever needed). That version measured a c=64 ratio of
0.91, down from the 0.93–1.03 pre-streaming band. Removing the pipe/thread
(the transform now runs inline inside that callback — see below) brought
c=64 back to 0.94, inside the original band. One run each per pass, not a
statistically rigorous sweep — reproduce before trusting the exact
numbers.

Reproduce: `./gradlew :osproxy-server:test -PincludeIntegration --tests PerfHarnessE2eTest`

## Memory footprint under sustained load

20,000 sequential requests, 256MiB-capped heap, forced GC before each RSS
snapshot. **Re-measured 2026-07-05**, after the pipe/thread fix:

| | RSS | Pipe/thread version | Pre-streaming |
|---|---|---|---|
| Idle | 117.9 MiB | 117.9 MiB | ~116 MiB |
| After soak | 239.0 MiB (2.03x, +121.1 MiB) | 281.4 MiB (2.39x, +163.5 MiB) | ~231 MiB (2.0x, +115 MiB) |

Soak growth with the pipe/thread design was +163.5 MiB, a real regression
against the +115 MiB pre-streaming baseline — a `PipedOutputStream`/
`PipedInputStream` pair plus a dedicated virtual thread per eligible
request, instead of one contiguous buffer, costs measurable memory over
20,000 requests. Removing the pipe/thread brought growth back to +121.1
MiB, essentially matching the pre-streaming number. Idle is unchanged
throughout (streaming adds no fixed cost at rest).

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
`StreamingTransformBench` (2026-07-05) went further and measured what it
*costs*, at two levels: the token-level transforms alone, and the full
`Pipeline` call as it actually runs in production (`MemorySink`, no real
network — isolates the engine-side cost only). That first measurement
caught a real architecture bug, which was then fixed and re-measured —
both passes are worth keeping, since the bug is exactly the kind of
mistake a pipe-based streaming design invites.

**Transform-only** (parser/generator vs. tree), average time per op —
unaffected by the fix below, since neither version ever used a pipe here:

| Transform | 256B | 4KiB | 64KiB |
|---|---|---|---|
| `injectFields` (buffered) | 0.70µs | 7.44µs | 143.9µs |
| `injectFieldsStreaming` | 0.64µs | 4.65µs | 86.7µs |
| `wrapQuery` (buffered) | 0.93µs | 6.87µs | 144.3µs |
| `wrapQueryStreaming` | 0.91µs | 4.90µs | 88.2µs |

At the transform level, streaming is faster in wall-clock time at every
size tested — roughly 40% faster by 64KiB — though it allocates more
bytes per op (Jackson's streaming generator trades allocation for fewer
full-tree passes). `parseBulk` inverts this: the streaming line-by-line
parse is consistently *slower* than the batch `String.split` version (at
100 docs × 4KiB, 550µs vs. 275µs) — not disqualifying (`_bulk` streaming's
value is escaping `max-body-bytes`, not raw parse speed), but a real cost
worth knowing about.

**Full pipeline — first pass, with a `PipedOutputStream`/`PipedInputStream`
pair and a dedicated virtual-thread producer** (the original design:
"parse in one thread, write in another, connected by a pipe"):

| | 256B | 4KiB | 64KiB |
|---|---|---|---|
| `pipelineIngestBuffered` | 1.52µs | 11.55µs | 162.0µs |
| `pipelineIngestStreaming` | 18.53µs | 24.72µs | 478.6µs |
| Ratio | 12.2x slower | 2.1x slower | 3.0x slower |

This was a real, measured cost, not noise — slower and far more
allocation at every size, worst at the small end. It matched what the
e2e/soak numbers above independently showed at the time (c=64 ratio
0.91, soak growth +163.5 MiB) — three separate measurements agreeing the
architecture had a real problem.

**The fix**: Helidon's `outputStream(handler)` callback already runs
*synchronously on the calling thread* — the request's own virtual thread
gets direct, blocking access to the upstream connection's `OutputStream`.
There was never a reason for a second thread or a pipe; the parse
(`Fields.injectFieldsStreaming`/`Queries.wrapQueryStreaming`) can run
**inline inside that same callback**, reading the client body and writing
the transformed result straight to the upstream connection, one thread,
zero pipes. `Sink.writeStreaming`/`Reader.searchStreaming`/`countStreaming`
now take a `StreamTransform` closure instead of a pre-transformed
`InputStream`, and `Pipeline.ingestDocStreaming`/`searchStreaming` build
that closure directly rather than spawning `Thread.ofVirtual()`.

**Full pipeline — after the fix**, same benchmark:

| | 256B | 4KiB | 64KiB |
|---|---|---|---|
| `pipelineIngestBuffered` | 1.45µs | 10.56µs | 163.8µs |
| `pipelineIngestStreaming` | 1.89µs | 8.54µs | 129.0µs |
| Ratio | 1.3x slower | **0.81x (19% faster)** | **0.79x (21% faster)** |

Streaming ingest now matches or *beats* the buffered path at every size
except the very smallest, where a residual ~0.4µs gap remains (setting up
a parser/generator per call still costs something, just not a second
thread). Allocation is still higher for streaming (18KB/op vs. 5.6KB/op
at 256B) — the parser/generator setup allocates more than the buffered
tree reuse — but that cost no longer shows up in wall-clock time on this
JVM/GC. The e2e and soak numbers above confirm the same recovery
end-to-end (c=64 ratio back to 0.94, soak growth back to +121.1 MiB).

**What this means, plainly:** the original regression was a real
architecture bug — an unnecessary thread-hop and pipe, not something
inherent to streaming itself. Once removed, streaming ingest costs about
what the implicit performance claim always assumed it should: comparable
to or cheaper than buffering, since it skips materializing a full
document copy, without the thread/pipe tax undermining that on the small
end. Search streaming shares the exact same fix (same `StreamTransform`
pattern) but wasn't benchmarked in isolation here the way ingest was —
worth a follow-up JMH pass if it becomes a priority, though the e2e/soak
numbers already reflect its corrected cost too.

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
