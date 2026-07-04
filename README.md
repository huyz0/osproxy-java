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
| `osproxy-server` | The executable: Helidon SE ingress, bearer auth, reference tenancy |
| `osproxy-jmh` | JMH microbenchmarks (`./gradlew :osproxy-jmh:jmh`, gc profiler for allocations/op) |

The module dependency graph is downward-only and enforced by an ArchUnit test
(`osproxy-server/src/test/.../ModuleDagTest.java`), the analog of the Rust
repo's `cargo xtask arch` gate.

## Scope

This port currently covers the core data plane (the Rust project's M1–M3):
document CRUD, `_search`, `_count`, `_bulk`, `_mget`, `_msearch`, with all
three placement modes (dedicated cluster / dedicated index / shared index) and
full write↔read symmetry. Migration epochs, scroll/PIT affinity, TLS, capture,
and the observability planes are later milestones.

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
