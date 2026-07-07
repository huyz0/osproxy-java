# osproxy-java

A multi-tenant OpenSearch routing proxy you run as a Java 25 / Helidon SE 4
library (virtual threads), built as a Gradle multi-project. Each request
routes to a physical cluster and index based on a pluggable, partition-based
placement policy, with write/read symmetry, epoch-gated migration, and
shape-only observability built in.

It is a from-scratch port of [osproxy](https://github.com/huyz0/opensearch-proxy),
a Rust library with the same design. See the [User Guide](site/) for the full
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

## Installing

Every module except `osproxy-server` (the reference binary) and `osproxy-jmh`
(benchmarks) publishes to Maven Central under `io.github.huyz0`:

```kotlin
implementation("io.github.huyz0:osproxy-engine:1.0.0")
implementation("io.github.huyz0:osproxy-config:1.0.0")
// + osproxy-kafka / osproxy-otlp if you use async fan-out or tracing
```

## Releasing

Publishing goes through the [Central Portal](https://central.sonatype.com/)
via the `com.vanniktech.maven.publish` plugin (`osproxy.publish-conventions`,
applied to every library module), driven by `.github/workflows/release.yml`
on a pushed tag — never run locally. One-time setup:

1. Create a Central Portal account and verify the `io.github.huyz0` namespace
   (Account → Namespaces → verify via GitHub OAuth — no domain needed).
2. Generate a user token (Account → Generate User Token).
3. Generate a GPG key (`gpg --quick-generate-key`) dedicated to signing
   releases, and publish it to a keyserver
   (`gpg --keyserver keyserver.ubuntu.com --send-keys <fingerprint>`);
   Central Portal verifies signatures against public keyservers.
4. Add four **repository secrets** (Settings → Secrets and variables →
   Actions) — never commit these:

   | Secret | Value |
   | --- | --- |
   | `MAVEN_CENTRAL_USERNAME` | the user token's username half |
   | `MAVEN_CENTRAL_PASSWORD` | the user token's password half |
   | `GPG_SIGNING_KEY` | `gpg --export-secret-keys --armor <fingerprint>` output, in full |
   | `GPG_SIGNING_KEY_PASSWORD` | the key's passphrase |

To cut a release: bump `version` in
`build-logic/src/main/kotlin/osproxy.java-conventions.gradle.kts`, commit,
then tag and push:

```sh
git tag v1.0.0 && git push origin v1.0.0
```

The `guard` job fails the run if the tag doesn't match the workspace version
(so a mistagged push can never publish under the wrong coordinate); the
`maven-central` job then runs `./gradlew publishToMavenCentral`, which stages
and auto-releases every library module in one deployment (Central Portal
validates checksums, signatures, and POM completeness before it goes live —
no separate close/release step like the old OSSRH staging flow).

For a local dry run against a throwaway key first (no Central Portal
credentials involved), `./gradlew publishToMavenLocal` exercises the same
jar/sources/javadoc/POM/signing pipeline against `~/.m2/repository`.

## Development

Docker engine 29+ note: docker-java's default API version (1.32) is below the
engine's minimum (1.40), which breaks Testcontainers' environment detection
with an opaque 400. The build already pins docker-java 3.5+; if detection
still fails, add `api.version=1.44` to `~/.docker-java.properties`.

Git hooks (`git config core.hooksPath .githooks`, set automatically on fresh
clones by `scripts/setup-hooks.sh`) enforce conventional commits and run the
gate before every commit.
