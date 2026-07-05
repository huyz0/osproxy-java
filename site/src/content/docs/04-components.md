---
title: "4. Components (Module View)"
---
osproxy-java is a Gradle multi-project build of small, single-responsibility
modules. The golden rule is a **strict downward dependency direction**,
enforced per module by an ArchUnit test: lower modules never depend on
higher ones. `osproxy-core` depends on nothing in the build; only the
`osproxy-server` binary depends on everything.

## Module diagram

```mermaid
%%{init: {'theme':'base','themeVariables':{'primaryColor':'#e8f0fe','primaryTextColor':'#0b1f33','primaryBorderColor':'#1a73e8','lineColor':'#5f6368','fontSize':'13px'}}}%%
flowchart TB
    server["osproxy-server<br/><i>the binary: Main, AppHandler, reference wiring</i>"]
    engine["osproxy-engine<br/><i>Pipeline orchestration</i>"]
    sink["osproxy-sink<br/><i>Sink + Reader, OpenSearchSink</i>"]
    rewrite["osproxy-rewrite<br/><i>bulk demux, query rewrite, strip</i>"]
    tenancy["osproxy-tenancy<br/><i>TenancySpi → Router, placement table</i>"]
    observe["osproxy-observe<br/><i>directives, /_osproxy/explain, break-glass</i>"]
    otlp["osproxy-otlp<br/><i>OTLP HTTP exporter</i>"]
    capture["osproxy-capture<br/><i>Capture seam, redaction</i>"]
    kafka["osproxy-kafka<br/><i>KafkaAckProducer (async writes + capture)</i>"]
    config["osproxy-config<br/><i>typed ProxyConfig load/validate</i>"]
    spi["osproxy-spi<br/><i>public interfaces you implement</i>"]
    core["osproxy-core<br/><i>types, model, ScopedValue seams · no I/O</i>"]
    jmh["osproxy-jmh<br/><i>JMH microbenchmarks + bench vocabulary</i>"]

    server --> engine & sink & tenancy & config & observe & capture & otlp & kafka & rewrite & spi & core
    engine --> sink & tenancy & rewrite & spi & core
    tenancy --> spi & core & rewrite
    sink --> spi & core
    rewrite --> core
    observe --> core
    otlp --> observe
    capture --> core
    kafka --> capture
    config --> core
    spi --> core
    jmh -.->|jmh sourceset| rewrite & engine & tenancy & spi & core & sink

    classDef bin fill:#fce8e6,stroke:#d93025,stroke-width:1.6px,color:#3c0a08;
    classDef mid fill:#e8f0fe,stroke:#1a73e8,stroke-width:1.4px,color:#0b1f33;
    classDef base fill:#e6f4ea,stroke:#188038,stroke-width:1.4px,color:#0b1f33;
    classDef aux fill:#f3e8fd,stroke:#a142f4,stroke-width:1.4px,color:#2a0b3c;
    class server bin;
    class engine,sink,rewrite,tenancy mid;
    class spi,core base;
    class observe,otlp,capture,kafka,config,jmh aux;
```

## Module responsibilities

| Module | Owns | Depends on |
|--------|------|------------|
| **osproxy-core** | Vocabulary types (`PartitionId`, `ClusterId`, `Target`, `Epoch`, `IndexName`…), the `Clock` seam, W3C `TraceContext`, and the `Tracing`/`ForwardHeaders` `ScopedValue` bindings. **No I/O.** | (nothing) |
| **osproxy-spi** | The public interfaces you implement: `TenancySpi`, the value types (`Placement`, `RequestCtx`, `RouteDecision`, `SpiException` hierarchy…). | core |
| **osproxy-rewrite** | NDJSON/`_bulk` demux, query-DSL partition-filter wrap, response field-strip, doc-id construction and inversion, partition extraction. | core |
| **osproxy-tenancy** | Adapts your `TenancySpi` into the engine's `Router` seam (`TenancyRouter`); the in-memory epoch-versioned `PlacementTable`; the SharedIndex partition-in-id invariant. | spi, core, rewrite |
| **osproxy-sink** | The `Sink` (write) and `Reader` (get/search/count/cursor/verbatim-forward) interfaces + `OpenSearchSink` (Helidon `WebClient`, per-cluster pools, circuit breaker) and `MemorySink` for tests. | spi, core |
| **osproxy-engine** | The request **`Pipeline`**: classify → resolve → write-gate → transform → dispatch → reverse-transform. The tenant-agnostic `PassthroughPolicy` short-circuit lives here too. | sink, tenancy, rewrite, spi, core |
| **osproxy-observe** | `Directive`/`DirectiveSet` (level/targeting/TTL/sampling), `ExplainDoc`/`ExplainStore`, `BreakGlassBuffer`, `DiagnosticSink` seam, `Metrics`, `SpanExporter` seam. | core |
| **osproxy-otlp** | `OtlpHttpExporter`: POSTs the pure OTLP/JSON span encoding to a collector, fire-and-forget with a bounded in-flight semaphore. | observe |
| **osproxy-capture** | The `Capture` seam for tenant-agnostic, full-fidelity traffic capture (`Capture.Record`, `Capture.redacting(...)`, `MemoryCapture`, `AckProducer`). | core |
| **osproxy-kafka** | `KafkaAckProducer` (acks=all, idempotent), the concrete broker-backed implementation both async writes and capture can use. | capture |
| **osproxy-config** | `ProxyConfig`: typed load/validate from Helidon `Config` (file + `OSPROXY_*` env), all defaults applied once, plus a named `Builder` for tests. | core |
| **osproxy-server** | The `osproxy-server` binary: `Main`, `AppHandler` (the Helidon ingress), `BearerAuth`, `ReferenceTenancy`, `PollingDirectiveStore`/`PollingPlacementStore`, `CryptoPosture` (FIPS engagement). | everything above |
| **osproxy-jmh** | JMH microbenchmarks (dimensional: doc size × bulk size × threads) and the `io.osproxy.bench` vocabulary (`LatencySummary`, `PerfProfile`, `FootprintProfile`) the e2e perf/soak tests in `osproxy-server` also use. | rewrite, engine, tenancy, spi, core, sink (jmh sourceset only) |

## Why this shape

- **The SPI is the narrow waist.** You compile against `osproxy-spi` (+
  `osproxy-core` types). Everything above it is the proxy's job; everything
  you provide is below the pipeline.
- **Seams, not frameworks.** `Sink`, `DirectiveSet.Store`, `SpanExporter`,
  `DiagnosticSink`, `Clock`, `CursorCodec` are interfaces with in-tree
  default implementations. You swap what you need; the rest stays at a
  near-zero-cost default (`NOOP` sinks, `InMemoryStore`).
- **The dependency DAG is a test, not a convention.** Each module has an
  ArchUnit test asserting only downward edges; a new module that tries to
  import upward fails `./gradlew check` immediately, not at review time.

→ [The SPI](/osproxy-java/05-spi-guide/)
