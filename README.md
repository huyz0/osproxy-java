# osproxy-java

A Java port of [osproxy](https://github.com/huyz0/opensearch-proxy), the
multi-tenant OpenSearch proxy. Same architecture, same isolation guarantees,
same SPI shape — built on Java 25 and Helidon SE 4 (virtual threads), as a
Gradle multi-project whose modules mirror the Rust workspace's crates:

| Module | Role |
| --- | --- |
| `osproxy-core` | Zero-I/O vocabulary: ids, endpoint kinds, targets, error codes |
| `osproxy-spi` | The SPI a user implements: `TenancySpi`, `RequestCtx`, `Placement` |
| `osproxy-rewrite` | Pure transforms: inject/strip fields, doc-id mapping, bulk demux, query wrapping |
| `osproxy-tenancy` | Adapts a `TenancySpi` into routing decisions; placement table |
| `osproxy-sink` | Write/read destination seam: OpenSearch HTTP sink + in-memory test sink |
| `osproxy-engine` | The pipeline: classify → resolve → rewrite → dispatch → shape response |
| `osproxy-config` | Typed config from `application.yaml` + `OSPROXY_*` env overrides |
| `osproxy-observe` | Shape-only observability: metrics, explain store, diagnostics directives |
| `osproxy-capture` | Traffic-capture and acked queue-producer seams (broker-free) |
| `osproxy-kafka` | The real `AckProducer` over kafka-clients (`acks=all`, idempotent) |
| `osproxy-otlp` | OTLP/HTTP span export (fire-and-forget, drop-if-saturated) |
| `osproxy-server` | The executable: Helidon SE ingress, bearer auth, reference tenancy |
| `osproxy-jmh` | JMH microbenchmarks + the perf-report vocabulary (`LatencySummary`/`PerfProfile`) |

The module dependency graph is downward-only and enforced by an ArchUnit test
(`osproxy-server/src/test/.../ModuleDagTest.java`), the analog of the Rust
repo's `cargo xtask arch` gate.

## Scope

The port covers the Rust project's M1–M7 arc:

- **Data plane** (M1–M3): document CRUD, `_search`, `_count`, `_bulk`,
  `_mget`, `_msearch`, all three placement modes, full write↔read symmetry.
- **Ingress hardening** (M4): TLS/mTLS from PEM paths, a TLS-for-mutation
  gate, request-body cap (413), upstream timeouts and a per-cluster circuit
  breaker.
- **Cursors**: scroll and PIT lifecycles with HMAC-sealed cluster affinity
  (`osproxy.cursor-affinity-key`); forged cursor ids are refused.
- **Migration** (M5): the SETTLED→DRAINING→CUTOVER state machine with a live
  epoch write gate — writes hold during cutover, reads never do.
- **Observability** (M7): W3C trace propagation (ScopedValue-bound, injected
  at the sink choke point), `GET /_osproxy/metrics`, per-request explain docs
  at `GET /_osproxy/explain/<id>`, optional JSON request logs, and a
  fail-closed diagnostics-directive plane published live through
  `POST /_osproxy/admin/directives`. A `ring_buffer: true` directive also
  turns on the break-glass tape: a bounded, in-order buffer of recent
  explanations readable at `GET /_osproxy/breakglass` — for pulling the last
  N failures of a class when the request ids aren't known up front, off (and
  free) until an operator flips it on.
- **Capture & async writes**: traffic-capture and acked-producer seams,
  plus the real Kafka producer (`osproxy.fanout.bootstrap-servers` +
  `fanout.topic`, `acks=all`, idempotent) behind per-request async write
  mode (`x-osproxy-write-mode: async` → honest `202 {status, op_id}` only
  after the broker acknowledged — e2e-verified against a real broker).
- **FIPS posture** (M6): the TLS listener is always pinned to the approved
  set (TLS 1.2/1.3, AES-GCM only, live-negotiation-tested), and
  `osproxy.fips: true` engages the bundled BouncyCastle FIPS module (the
  CMVP-validated BC-FIPS 2.1 line from Maven Central) in approved-only
  mode, first in the provider order — failing boot loudly if the module's
  self-tests refuse. Dormant unless enabled.
- **OTLP export**: `osproxy.otlp-endpoint` turns on fire-and-forget span
  export (`osproxy-otlp`): one shape-only SERVER span per request POSTed to
  `{endpoint}/v1/traces`, bounded in-flight budget that sheds spans behind a
  slow collector, never on the request path.
- **Fleet directives & placements**: `osproxy.directives-url` and
  `osproxy.placements-url` poll their documents from any HTTP source (an
  etcd gateway, a config service, an object store) with fail-closed
  decoding and keep-last-good semantics — every instance polling the same
  URL converges without restarts. Placement changes bump the partition's
  epoch only on a real move, engaging the stale-write gate. A directive can
  also target one authenticated `principal`, not just tenant/index/endpoint.
- **Diagnostic sink**: `osproxy.log-diagnostic-captures` pushes each
  `ring_buffer`-selected explain doc to stdout as a tagged JSON line too, so
  a log-collector-backed aggregator can see fleet-wide captures, not just
  the instance that happened to handle the request (the break-glass ring
  stays local per-instance either way).
- **`/_osproxy/explain` and `/_osproxy/breakglass` kill-switch**:
  `osproxy.debug-endpoints` (default true) turns both off in production;
  disabled requests report `not_enabled` rather than 404.
  `/_osproxy/metrics` always stays on regardless.
- **Tenant-agnostic passthrough**: `osproxy.passthrough-cluster` +
  `osproxy.passthrough-endpoint` (+ optional `osproxy.passthrough-indices`,
  a comma-separated prefix list) forward matching requests verbatim to one
  cluster, skipping tenancy entirely — the composable migration shape:
  legacy indices pass through, onboarded ones stay tenant-isolated, fail-closed
  on a non-match. `osproxy.header-forwarding.enabled` (default true) and
  `.deny` control which client headers ride along on a passthrough forward
  (mandatory hop-by-hop/framing headers are always stripped).

Everything is shape-only on the wire: error bodies, metrics, explain docs,
spans and logs never carry tenant values.

## Build and test

Requires Java 25 (the build resolves it via Gradle toolchains; SDKMAN works
well) and Docker for the integration tests.

```sh
./gradlew check                                        # unit tests + DAG + coverage floor
./gradlew :osproxy-server:test -PincludeIntegration    # + Testcontainers e2e vs real OpenSearch
./gradlew :osproxy-server:run                          # run the reference server (ZGC)
```

### Benchmarks

Two layers, mirroring the Rust project's osproxy-bench:

- **Microbenchmarks** (`./gradlew :osproxy-jmh:jmh`, narrow with
  `-Pjmh.includes=Dimensional`): the hot transforms swept across document
  size (256B/4KiB/64KiB), bulk batch size (10/100/1000 docs), and thread
  count (8-thread contended variants), with the gc profiler reporting
  allocations/op per cell.
- **E2e perf harness** (`PerfHarnessE2eTest`, integration-tagged): the same
  ingest workload direct-vs-proxied against a real OpenSearch container,
  swept across concurrency (1/8/32/64), reported as nearest-rank
  p50/p95/p99 plus added latency and throughput ratio per level.
  Assertions are host-independent (all requests succeed, throughput rises
  with concurrency); the numbers print for a human or an LLM judge.
  Measured on this box: throughput 91 → 3596 ops/s from c=1 to c=64,
  throughput ratio 0.93–1.03 throughout, added p50 near zero (some levels
  measure negative — noise at this sample size).
- **Footprint/soak** (`SoakE2eTest`, integration-tagged, Linux-only): spawns
  the real `osproxy-server` artifact as its own OS process (not
  in-process) so `/proc/<pid>/statm` reports the proxy's actual resident
  set; forces a GC via `jcmd` before each snapshot so RSS reflects the
  live set, not uncollected garbage. Drives 20k sequential requests and
  judges growth on an either/or bound (ratio OR absolute — a tiny idle
  footprint makes a small absolute growth look huge as a ratio). Measured
  on this box (256 MiB heap cap): idle ~116 MiB, soak ~231 MiB (1.99x,
  ~115 MiB) — bounded, not a leak.

Docker engine 29+ note: docker-java's default API version (1.32) is below the
engine's minimum (1.40), which breaks Testcontainers' environment detection
with an opaque 400. The build already pins docker-java 3.5+, and if detection
still fails, add `api.version=1.44` to `~/.docker-java.properties`.

Git hooks (`git config core.hooksPath .githooks`, set automatically on fresh
clones by `scripts/setup-hooks.sh`) enforce conventional commits and run the
gate before every commit.
