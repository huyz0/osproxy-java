# osproxy-java

A multi-tenant OpenSearch routing proxy you run as a Java 25 / Helidon SE 4
library (virtual threads), built as a Gradle multi-project. Each request
routes to a physical cluster and index based on a pluggable, partition-based
placement policy, with write/read symmetry, epoch-gated migration, and
shape-only observability built in.

It is a from-scratch port of [osproxy](https://github.com/huyz0/opensearch-proxy),
a Rust library with the same design — see the [User Guide](site/) for the full
story; this README is the quickstart.

## Quickstart

```sh
./gradlew check                                        # unit tests + module DAG + coverage floor
./gradlew :osproxy-server:test -PincludeIntegration    # + Testcontainers e2e vs real OpenSearch
./gradlew :osproxy-server:run                          # run the reference server
```

Requires Java 25 (resolved via Gradle toolchains; SDKMAN works well) and
Docker for the integration tests.

```java
var cluster = new ClusterId("primary");
var sink = new OpenSearchSink(Map.of(cluster, "http://localhost:9200"));
var tenancy = new ReferenceTenancy(cluster, new IndexName("shared"));

Pipeline pipeline = new Pipeline(new TenancyRouter(tenancy), sink, sink);
AppHandler handler = new AppHandler(pipeline, new BearerAuth(Map.of()));

WebServer.builder().port(9200).routing(handler::route).build().start();
```

## Modules

| Module | Role |
| --- | --- |
| `osproxy-core` | Zero-I/O vocabulary: ids, endpoint kinds, targets, trace/forward-header seams |
| `osproxy-spi` | The SPI you implement: `TenancySpi`, `RequestCtx`, `Placement` |
| `osproxy-rewrite` | Pure transforms: inject/strip fields, doc-id mapping, bulk demux, query wrapping |
| `osproxy-tenancy` | Adapts a `TenancySpi` into routing decisions; placement table |
| `osproxy-sink` | Write/read destination seam: OpenSearch HTTP sink + in-memory test sink |
| `osproxy-engine` | The pipeline: passthrough check → resolve → rewrite → dispatch → shape response |
| `osproxy-config` | Typed config from `application.yaml` + `OSPROXY_*` env overrides |
| `osproxy-observe` | Shape-only observability: metrics, explain store, break-glass, diagnostics directives |
| `osproxy-capture` | Traffic-capture and acked queue-producer seams (broker-free) |
| `osproxy-kafka` | The real `AckProducer` over kafka-clients (`acks=all`, idempotent) |
| `osproxy-otlp` | OTLP/HTTP span export (fire-and-forget, drop-if-saturated) |
| `osproxy-server` | The executable: Helidon SE ingress, bearer auth, reference tenancy |
| `osproxy-jmh` | JMH microbenchmarks + the perf-report vocabulary (`LatencySummary`/`PerfProfile`) |

The dependency graph is downward-only, enforced per module by an ArchUnit
test. See [Components](site/src/content/docs/04-components.md) for the full
diagram.

## Documentation

The [`site/`](site/) directory is an Astro + Starlight guide (`npm install &&
npm run dev` from `site/`) covering, in order: overview, requirements/NFRs,
architecture, components, the SPI, wiring examples, configuration,
observability & the control plane, async fan-out writes, choosing a mode, and
measured performance (including a controlled comparison against the Rust
sibling project). Start at [`site/src/content/docs/index.md`](site/src/content/docs/index.md)
if you're reading it as plain markdown, or run the site locally for
navigation and diagrams.

## Development

Docker engine 29+ note: docker-java's default API version (1.32) is below the
engine's minimum (1.40), which breaks Testcontainers' environment detection
with an opaque 400. The build already pins docker-java 3.5+; if detection
still fails, add `api.version=1.44` to `~/.docker-java.properties`.

Git hooks (`git config core.hooksPath .githooks`, set automatically on fresh
clones by `scripts/setup-hooks.sh`) enforce conventional commits and run the
gate before every commit.
