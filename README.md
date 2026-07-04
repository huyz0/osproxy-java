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
| `osproxy-capture` | Traffic-capture and acked queue-producer seams (no broker linked) |
| `osproxy-server` | The executable: Helidon SE ingress, bearer auth, reference tenancy |
| `osproxy-jmh` | JMH microbenchmarks (`./gradlew :osproxy-jmh:jmh`, gc profiler for allocations/op) |

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
  `POST /_osproxy/admin/directives`.
- **Capture & async writes**: traffic-capture and acked-producer seams
  (broker-free by default) and per-request async write mode
  (`x-osproxy-write-mode: async` → honest `202 {status, op_id}`).

Everything is shape-only on the wire: error bodies, metrics, explain docs and
logs never carry tenant values. Deferred: FIPS crypto posture, OTLP export,
a distributed directive/placement store (the seams exist; the backends are
deployment choices).

## Build and test

Requires Java 25 (the build resolves it via Gradle toolchains; SDKMAN works
well) and Docker for the integration tests.

```sh
./gradlew check                                        # unit tests + DAG + coverage floor
./gradlew :osproxy-server:test -PincludeIntegration    # + Testcontainers e2e vs real OpenSearch
./gradlew :osproxy-server:run                          # run the reference server (ZGC)
```

Docker engine 29+ note: docker-java's default API version (1.32) is below the
engine's minimum (1.40), which breaks Testcontainers' environment detection
with an opaque 400. The build already pins docker-java 3.5+, and if detection
still fails, add `api.version=1.44` to `~/.docker-java.properties`.

Git hooks (`git config core.hooksPath .githooks`, set automatically on fresh
clones by `scripts/setup-hooks.sh`) enforce conventional commits and run the
gate before every commit.
