---
title: "2. Requirements & Non-Functional Requirements"
---
Every requirement here maps to code you can read and tests you can run; this
page is the summary, not aspirational.

## Functional scope

- **Ingress**: HTTP/1.1 and HTTP/2 (negotiated automatically, same port) on
  Helidon SE's virtual-thread server; cleartext and TLS/mTLS. A gRPC
  `DocumentService` (single-doc ingest) rides the same port too, mirroring the
  Rust `osproxy` sibling's gRPC surface; see
  [Architecture](/osproxy-java/03-architecture/).
- **Single-target routing** for **all** request types (read and write).
- **Ingest demux**: one mixed-partition `_bulk` body split into per-placement
  writes, response `items[]` re-interleaved in original order.
- **Query rewrite** (mandatory partition filter) and **response
  field-stripping** for shared-index tenancy.
- **Doc-id construction** and **partition-field injection** on ingest.
- **Connection pooling**: per-cluster `WebClient` pools with a circuit
  breaker upstream; virtual threads downstream (no thread-per-request cost).
- **Auth**: bearer-token client authentication. Upstream authentication is
  either the client's own forwarded credential (pass-through) or an SPI-
  supplied one (`TenancySpi.upstreamCredentials`, resolved fresh per route,
  overwrites a same-named forwarded header) — see
  [The SPI](/osproxy-java/05-spi-guide/). Upstream TLS/mTLS
  (`osproxy.upstream-tls.*`) is independent of ingress TLS and fails closed:
  an `https://` cluster with no trust anchor configured is refused, never
  silently dialed in cleartext or trusted via the JDK's platform store.
- **Scroll/PIT affinity** pinning (opt-in, HMAC-sealed).
- **Epoch-gated partition migration**.
- **Pluggable write sink** (OpenSearch now; the `Sink` interface makes a
  Kafka-backed async write mode a drop-in, see `osproxy-kafka`).
- **Tenant-agnostic passthrough** (opt-in, index-prefix scoped).
- **Runtime-togglable, shape-only observability.**

### Supported endpoint matrix

Each request is classified into one endpoint kind (`Classify.classify`) and
dispatched accordingly:

| Kind | Examples | Tenancy-aware |
|------|----------|---------------|
| `INGEST_DOC` | `PUT/POST /idx/_doc/{id}`, `/idx/_create/{id}` | yes (inject + construct id) |
| `INGEST_BULK` | `POST /_bulk`, `/idx/_bulk` | yes (per-doc demux) |
| `GET_BY_ID` | `GET /idx/_doc/{id}` | yes (id mapping) |
| `MULTI_GET` | `GET/POST /_mget` | yes (per-doc) |
| `DELETE_BY_ID` | `DELETE /idx/_doc/{id}` | yes |
| `SEARCH` | `POST /idx/_search` | yes (filter + strip) |
| `MULTI_SEARCH` | `POST /_msearch` | yes (per-query) |
| `COUNT` | `POST /idx/_count` | yes (filter) |
| `DELETE_BY_QUERY` | `POST /idx/_delete_by_query` | yes (async-mode only, opt-in expansion) |
| `CURSOR` | `_search/scroll`, `_search/point_in_time` | affinity-pinned |
| `ADMIN` | `_cat`, `_cluster`, `_nodes` | refused by default; opt-in allow-list pass-through |

A request whose logical index matches a configured passthrough policy skips
this table entirely and forwards verbatim, see
[Choosing a Mode](/osproxy-java/10-choosing-a-mode/).

## Non-functional requirements

Grouped the same way the Rust sibling groups them, so the two are directly
comparable; a Java-specific note follows where the platform changes what
"met" looks like.

### Performance (NFR-P)

| Id | Requirement | Status here |
|----|-------------|-------------|
| NFR-P1 | Added p50 latency over direct-to-cluster stays small for a simple write. | Measured near-zero at c=1–32, up to +0.8ms at c=64 (JVM sampling noise territory), see [Performance](/osproxy-java/11-performance/). |
| NFR-P2 | Added p99 latency under budget; no tail amplification from pooling. | Measured; grows with concurrency but throughput scales with it (pool reuse, not serialization). |
| NFR-P3 | Minimal allocation on the pass-through hot path. | Not GC-budget-gated the way the Rust build is `dhat`-gated; JMH benches measure allocations/op but there is no CI budget gate yet. |
| NFR-P4 | Upstream TLS/connection reuse under steady load. | `WebClient` pools per cluster; reuse verified qualitatively, not numerically gated. |
| NFR-P5 | Downstream keep-alive honored; no per-request connection churn. | Default Helidon SE behavior. |
| NFR-P6 | Idle memory footprint bounded; no unbounded buffers/queues. | Measured: idle ~11MiB (Rust) vs ~116MiB (JVM baseline), a platform difference, not a leak; soak growth stays bounded (~2x, ~115MiB) under a capped heap. |
| NFR-P7 | Bulk demux is single-pass over the body. | Yes (Jackson streaming tokenization, no full-tree re-serialization on the hot path). |

### Reliability (NFR-R)

| Id | Requirement | Status here |
|----|-------------|-------------|
| NFR-R1 | No uncaught exception escapes the request path. | Enforced by a top-level catch in `Pipeline.handle` mapping every typed exception to a wire error; not statically enforced by a linter the way Rust's `deny(unwrap_used)` is. |
| NFR-R2 | Every fallible operation returns a typed error from the taxonomy. | `SpiException`/`SinkException`/`RewriteException`/`EngineException` hierarchy, each carrying an `ErrorCode`. |
| NFR-R3 | Backpressure: bounded body size; overload signaled, never OOM. | Request-body cap enforced incrementally (works for chunked bodies too). |
| NFR-R4 | Upstream failures classified and surfaced with context. | Circuit breaker + `ErrorCode` on every sink exception. |
| NFR-R6 | No data corruption across partition migration. | Epoch-gated write admission (`MigrationGatedTenancy`), same invariant as the Rust sibling, tested with the same INV-M1..M4 simulations ported. |

### Traceability / observability (NFR-T)

| Id | Requirement |
|----|-------------|
| NFR-T1 | Every request emits one causal trace whose spans reconstruct why it routed where it did. |
| NFR-T2 | Default verbosity emits shapes, ids, and field names only, never tenant values, bodies, tokens, or credentials. |
| NFR-T3 | Verbosity is runtime-togglable fleet-wide without restart, targeted by tenant/index/principal/endpoint, with TTL auto-expiry. |
| NFR-T4 | `GET /_osproxy/explain/{request_id}` returns the decision-relevant shape as one JSON document. |
| NFR-T5 | `GET /_osproxy/breakglass` returns a bounded, in-order tape of recent captures selected by a `ring_buffer` directive, for pulling the last N failures of a class when the ids aren't known up front. |

### Security (NFR-S)

| Id | Requirement |
|----|-------------|
| NFR-S1 | TLS required for any mutating request when `osproxy.require-tls-for-mutation` is set; no body-mutating cleartext passthrough. |
| NFR-S2 | No secret/credential/tenant value in any log or trace at any verbosity. |
| NFR-S3 | Header-delivered debug directives are HMAC-signed; clients cannot self-enable expensive tracing. |
| NFR-S4 | Partition isolation enforced on the read path; a client-supplied query cannot bypass the partition filter. |
| NFR-S5 | The FIPS build (`osproxy.fips=true`) engages BouncyCastle FIPS in approved-only mode and pins the TLS listener to an approved cipher/protocol set. |
| NFR-S6 | An `https://` upstream cluster with no `osproxy.upstream-tls.ca-path` configured is refused (`SinkException`), never dialed in cleartext or trusted via an implicit platform trust store. |

### Maintainability / quality (NFR-Q)

| Id | Requirement |
|----|-------------|
| NFR-Q1 | Downward-only module dependency DAG, enforced by an ArchUnit test per module. |
| NFR-Q2 | ≥ 90% JaCoCo line coverage per module (the `osproxy-server` main-wiring class is exempted at a lower floor via a per-module `coverageFloor` property). |
| NFR-Q3 | Public SPI types carry doc comments with intent and invariants. |

## Release acceptance

A release is acceptable when: the endpoint matrix routes correctly and
round-trips symmetrically (verified by property tests + e2e against a real
OpenSearch container); `./gradlew check` is green (JaCoCo, ArchUnit, tests)
across every module; no value leaks at default verbosity; and (for FIPS
builds) the bundled BC-FIPS module engages and negotiates only the approved
TLS suite set.

→ [Architecture](/osproxy-java/03-architecture/)
