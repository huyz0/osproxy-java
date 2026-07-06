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
inject+serialize cost scales from ~0.7µs at 2KiB up to ~139µs at 187KiB.
Jackson tree materialization is the marked optimization target for a future
streaming-twin pass, not yet done.

## Per-request added latency and throughput (e2e, real OpenSearch)

Ingest workload, direct-to-cluster baseline vs. through the proxy, swept
c=1/8/32/64 (300 ops/level in CI). Streaming ingest is the default path
for any tenancy where it's eligible (`Pipeline.supportsStreamingIngest`,
true for the reference tenancy), so this reflects what actually runs.

A single run at this sample size is not enough to trust: baseline and
proxied percentiles are independently sampled, so at low concurrency
(where absolute latencies are small and dominated by host jitter) the
apparent "added latency" can land negative purely from noise, not because
the proxy is actually faster than talking to OpenSearch directly, that
would be physically impossible for a request that goes through the proxy
*and then* to OpenSearch. Five runs, measured 2026-07-06:

| Concurrency | Added p50 across 5 runs | Added p99 across 5 runs | Throughput ratio across 5 runs |
|---|---|---|---|
| 1 | -0.10 to +0.83ms (median +0.65ms) | -2.20 to +2.37ms (median -1.65ms) | 0.94-1.02 |
| 8 | -4.43 to +0.94ms (median +0.01ms) | -16.26 to +8.92ms (median -0.79ms) | 0.83-1.22 |
| 32 | +0.22 to +1.78ms (median +1.16ms) | +0.45 to +15.47ms (median +3.95ms) | 0.90-1.07 |
| 64 | -0.80 to +3.27ms (median +1.88ms) | +6.17 to +12.36ms (median +8.59ms) | 0.77-0.94 |

The honest signal in this data: at c=1 and c=8, added latency is small
relative to measurement noise in both directions, not a reliable read
either way at this sample size. At c=32 and c=64, added p99 came out
**positive in every single one of the 5 runs**, a real, repeatable
tail-latency cost from routing through the proxy, consistent with what
should physically happen. Throughput ratio stays in a 0.77-1.22 band with
no consistent downward trend as concurrency rises (the streaming
transform, see [below](#streaming-transform-cost-jmh), runs inline on the
request's own virtual thread, so nothing here scales with thread count),
though the wide band itself says this harness isn't precise enough to
report a single ratio number with confidence, only that the proxy doesn't
collapse under concurrency.

Reproduce: `./gradlew :osproxy-server:test -PincludeIntegration --tests PerfHarnessE2eTest`,
several times, not once, before trusting any single number from it.

## Memory footprint under sustained load

4,000 sequential requests in CI (20,000 when this table was measured,
before the same scale-down), 256MiB-capped heap, forced GC before each
RSS snapshot. Measured 2026-07-05:

| | RSS |
|---|---|
| Idle | 117.9 MiB |
| After soak | 239.0 MiB (2.03x, +121.1 MiB) |

Idle is unchanged from the pre-streaming baseline (streaming adds no fixed
cost at rest); soak growth of +121.1 MiB is in the same range as
buffered-only ingest measured earlier (~+115 MiB), streaming a document
through instead of buffering it whole doesn't cost extra steady-state
memory, since there's no second thread or intermediate buffer in the path.

Reproduce: `./gradlew :osproxy-server:test -PincludeIntegration --tests SoakE2eTest`

## Rust vs Java, measured side by side

**This comparison predates streaming ingest and has not been re-run since.**
Read it as describing the pre-streaming buffered path, not necessarily
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

**A dead heat** at this workload, every gap is smaller than the
rep-to-rep spread of a single binary. OpenSearch's own write latency
dominates both; the proxy layer itself isn't the bottleneck for either
implementation here, so language/runtime differences don't show up in
throughput.

**Memory footprint is the one real, structural gap**: Rust idles at ~11
MiB RSS; Java idles at ~116 MiB. That's the JVM baseline (class metadata,
JIT, GC bookkeeping), not proxy logic, expected and not something a code
change closes. If footprint is your binding constraint (high pod density,
serverless cold starts), that's the number to weigh; if it's throughput or
p50/p99 latency under this kind of workload, the two are presently
indistinguishable.

This comparison is a single controlled run, not a statistically
rigorous study, read it as "same order of magnitude, footprint aside,"
not as a claim of parity down to the percentage point.

## Streaming passthrough bypasses the body cap entirely

`PassthroughE2eTest.aBodyLargerThanMaxBodyBytesStreamsThroughWithoutBeingCapped`
proxies a 16 MiB body through a proxy configured with a 1 KiB
`osproxy.max-body-bytes` cap and asserts the upstream receives all 16 MiB,
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
NDJSON item at a time, so the request is never buffered whole either.
`_bulk`'s existing line-oriented framing made it the second endpoint class to
close after passthrough.

Single-doc ingest closes the memory-buffering side of the same gap, but
deliberately keeps the cap: `Fields.injectFieldsStreaming` copies the
client's document token by token into a pipe that `OpenSearchSink.writeStreaming`
uploads directly, so a document up to `osproxy.max-body-bytes` costs one
streaming pass instead of a buffer copy plus a full Jackson tree, but a
document *over* the cap is still refused with `413`
(`IngressHardeningTest.overCapBodiesAreRefusedWith413`, pre-existing,
unmodified, still green), since that NFR predates streaming and streaming
doesn't get to silently drop it. Eligibility (`Pipeline.supportsStreamingIngest`)
depends on the physical target and id being derivable without reading the
body, true for the reference tenancy, false only for a body-derived
partition key.

Search and count close the same gap the same way:
`Queries.wrapQueryStreaming` streams the client's query into the
`bool`/`must`/`filter` enclosure token by token (`SearchStreamingE2eTest`
proves the wrapped shape lands on the wire correctly), except the one
`aggs`/`aggregations` clause, read as a tree because the unfilterable check
needs to see the whole subtree before deciding to refuse, proven
unaffected on the streaming path too
(`unfilterableSearchConstructsAreStillRefusedOnTheStreamingPath`). Search also
keeps enforcing `osproxy.max-body-bytes`
(`aSearchBodyLargerThanTheCapIsStillRefusedWith413`): a query is not the
kind of legitimately-large aggregate payload passthrough/`_bulk` exist to
escape, so the cap stays.

## Streaming transform cost (JMH)

The measurements above proved streaming *works* and preserves isolation.
`StreamingTransformBench` measures what it *costs*, at two levels: the
token-level transforms alone, and the full `Pipeline` call as it actually
runs in production (`MemorySink`, no real network, isolates the
engine-side cost only). `Sink.writeStreaming`/`Reader.searchStreaming`/
`countStreaming` take a `StreamTransform` closure that runs **inline
inside the upstream client's output-stream callback**, Helidon's
`outputStream(handler)` hands the destination `OutputStream` to the
calling (virtual) thread synchronously, so the transform
(`Fields.injectFieldsStreaming`/`Queries.wrapQueryStreaming`) reads the
client body and writes the transformed result straight to the upstream
connection on that same thread. One thread, no pipe, no queue.

**Transform-only** (parser/generator vs. tree), single-threaded, average
time per op:

| Transform | 256B | 4KiB | 64KiB |
|---|---|---|---|
| `injectFields` (buffered) | 0.70µs | 7.44µs | 143.9µs |
| `injectFieldsStreaming` | 0.64µs | 4.65µs | 86.7µs |
| `wrapQuery` (buffered) | 0.93µs | 6.87µs | 144.3µs |
| `wrapQueryStreaming` | 0.91µs | 4.90µs | 88.2µs |

At the transform level, streaming is faster in wall-clock time at every
size tested, roughly 40% faster by 64KiB, though it allocates more
bytes per op (Jackson's streaming generator trades allocation for fewer
full-tree passes).

`Bulk.parseBulkStream` used to invert this: an early version read NDJSON
line-by-line via `BufferedReader.readLine()`, materializing every line as
a `String` before re-parsing it with `Json.MAPPER.readTree(String)`, a
double decode/parse pass per line that made it consistently *slower* than
the batch `String.split` version (100 docs × 4KiB: 550µs vs. 275µs). It's
since been rewritten to be genuinely token-level: one shared `JsonParser`
reads directly off the request `InputStream`, calling `parser.nextToken()`
+ `Json.MAPPER.readTree(parser)` per successive NDJSON value, no line is
ever materialized as a `String` (NDJSON's newlines are just insignificant
whitespace to the tokenizer). That didn't just close the gap, it reversed
it, streaming is now faster than buffered at every size measured:

| | 10×256B | 10×4KiB | 100×256B | 100×4KiB | 1000×256B | 1000×4KiB |
|---|---|---|---|---|---|---|
| `parseBulk` (buffered) | 4.9µs | 31.0µs | 48.6µs | 269.5µs | 491.1µs | 3579.6µs |
| `parseBulkStream` (token-level) | 3.4µs | 17.0µs | 31.9µs | 171.8µs | 316.1µs | 1669.6µs |
| Ratio | 30% faster | 45% faster* | 34% faster | 36% faster | 36% faster | 53% faster |

(*10×4KiB carries a wide error bar, ±24.6µs on a 17.0µs mean, treat that
one cell as noisy, not a precise 45%; every other cell is a clean
measurement.) The value proposition for streaming bulk was always escaping
`max-body-bytes`, not raw parse speed, this result means it no longer
has to trade one for the other, which matters most for very large
per-line documents (a single bulk item has no size cap of its own), since
those are exactly the case where the old double-buffer-and-reparse cost
scaled worst.

**Full pipeline, single-threaded**, same benchmark:

| | 256B | 4KiB | 64KiB |
|---|---|---|---|
| `pipelineIngestBuffered` | 1.45µs | 10.56µs | 163.8µs |
| `pipelineIngestStreaming` | 1.89µs | 8.54µs | 129.0µs |
| Ratio | 1.3x slower | **0.81x (19% faster)** | **0.79x (21% faster)** |

Streaming ingest matches or *beats* the buffered path at every size except
the very smallest, where a residual ~0.4µs gap remains (setting up a
parser/generator per call costs something regardless of thread model).
Allocation is still higher for streaming (18KB/op vs. 5.6KB/op at 256B):
the parser/generator setup allocates more than the buffered tree reuse,
but that cost doesn't show up in wall-clock time on this JVM/GC.

**Full pipeline, 8 concurrent threads sharing one `Pipeline`/`MemorySink`,**
the same sharing shape one proxy instance has under real load, and the
dimension that actually matters for a proxy (see the e2e concurrency sweep
above):

| | 256B | 4KiB | 64KiB |
|---|---|---|---|
| `pipelineIngestBufferedConcurrent` | 2.04µs | 14.29µs | 256.4µs |
| `pipelineIngestStreamingConcurrent` | 3.66µs | 13.27µs | 195.5µs |
| Ratio | 1.79x slower | **0.93x (7% faster)** | **0.76x (24% faster)** |

The shape holds under 8-way contention: streaming stays behind buffered at
256B (worse than the single-threaded 1.3x, small-doc parser/generator
setup is the one part of this path with any per-call fixed cost, so it's
also the one part sensitive to CPU contention) but *widens its lead* at
4KiB and 64KiB, since it skips materializing the buffered path's full
tree copy regardless of how many threads are doing the same thing
simultaneously. Allocation per op is consistently higher for streaming at
every size (256B: 17.9KB vs 5.5KB; 64KiB: 833KB vs 255KB), same ratio as
single-threaded, meaning contention doesn't introduce a new allocation
cost, just amplifies the existing one. Nothing here points to shared-state
contention: both paths scale the way stateless-transform, per-request
closures should, the numbers move with allocator/GC pressure, not with a
lock.

**What this means, plainly:** streaming ingest costs about what the
implicit performance claim assumes it should, comparable to or cheaper
than buffering, since it skips materializing a full document copy, and
that holds under concurrent load because the transform runs inline on the
request's own thread with nothing shared to contend over. Search streaming
uses the exact same `StreamTransform` pattern but wasn't benchmarked in
isolation here the way ingest was, worth a follow-up JMH pass if it
becomes a priority, though the e2e numbers above already reflect its cost
too.

Reproduce: `./gradlew :osproxy-jmh:jmh -Pjmh.includes=Streaming`

## Concurrency load test

`ConcurrentLoadE2eTest` drives 2,000 ingest requests through 200
concurrent virtual-thread workers against a *mocked* upstream (a plain
Helidon `WebServer` that accepts everything and answers immediately),
deliberately not a real OpenSearch container, so the result isolates the
proxy's own behavior under heavy fan-out from anything upstream-side.
Result: **0 failures, 0 exceptions**, typically well under a couple of
seconds and a few hundred to a few thousand req/s depending on host
load (the CI box this runs on is shared, so the exact number moves
around; the invariant that matters is zero failures and no stall). No
connection-pool exhaustion, no virtual-thread pinning, no circuit-breaker
false trips, the proxy holds up cleanly at this concurrency.

The one real finding from building this test was in the *test harness*,
not the proxy: the first attempt launched one virtual thread per request
(one simultaneous new connection per request) and failed with `java.net.
ConnectException: Cannot assign requested address`. That's client-side
ephemeral-port exhaustion, this box's `/proc/sys/net/ipv4/
ip_local_port_range` is a narrow ~4096 ports, not a proxy defect; the fix
was bounding the harness to a fixed pool of concurrent workers pulling
from a shared counter (the same pattern `PerfHarnessE2eTest.run()`
already uses), which still drives real, heavy sustained concurrency
without the client artifact. Worth remembering if this test is ever
re-tuned: a failure here that looks like a connection error is worth
checking against the local ephemeral port range before assuming it's the
proxy. Request/worker counts were cut roughly 5x from the original
10,000/1,000 to keep the Docker-backed integration suite fast in CI; the
invariants above hold at either scale.

Reproduce: `./gradlew :osproxy-server:test -PincludeIntegration --tests ConcurrentLoadE2eTest`
(no Docker required, the upstream is mocked)

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
