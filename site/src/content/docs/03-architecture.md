---
title: "3. Architecture"
---
osproxy-java is a Gradle multi-module library and a reference binary
(`osproxy-server`). You implement the SPI; the engine runs each request
through a fixed pipeline (`Pipeline.handle`). Nothing on the hot path uses
reflection or dynamic class loading, your `TenancySpi` and `Sink` are wired
once in `main` and called directly.

## Request lifecycle

```mermaid
%%{init: {'theme':'base','themeVariables':{'primaryColor':'#e8f0fe','primaryTextColor':'#0b1f33','primaryBorderColor':'#1a73e8','lineColor':'#5f6368','fontSize':'13px'}}}%%
flowchart TB
    A["Client request"] --> B["Ingress (Helidon SE WebServer)<br/>parse · classify · body cap"]
    B --> C{"Pre-auth route?<br/>/_osproxy/metrics · /explain · /breakglass · /admin/directives"}
    C -- yes --> C1["Serve introspection/admin<br/>(short-circuit)"]
    C -- no --> D["TLS gate (NFR-S1)<br/>refuse mutating cleartext when configured"]
    D --> E["Authenticate<br/>(BearerAuth: token → Principal)"]
    E --> F["Passthrough check<br/>(PassthroughPolicy, if configured)"]
    F -- matches --> F1["Forward verbatim<br/>(Reader.forward, bypasses tenancy)"]
    F -- no match --> G["Resolve<br/>(TenancyRouter)<br/>partition → placement → target"]
    G --> H["Write gate (epoch)<br/>admit / stale-epoch 409"]
    H --> I["Transform (osproxy-rewrite)<br/>inject + construct id / filter + strip"]
    I --> J["Dispatch (osproxy-sink)<br/>per-cluster WebClient pool · circuit breaker"]
    J --> K["Reverse-transform<br/>strip injected fields from response"]
    K --> L["Response to client<br/>+ x-osproxy-request-id"]

    M["Observability (osproxy-observe)"] -. "shape-only ExplainDoc<br/>every stage" .- G
    M -. "/_osproxy/explain · OTLP · logs" .- L

    classDef step fill:#e8f0fe,stroke:#1a73e8,stroke-width:1.3px,color:#0b1f33;
    classDef gate fill:#fef7e0,stroke:#f9ab00,stroke-width:1.3px,color:#3c2a00;
    classDef obs fill:#f3e8fd,stroke:#a142f4,stroke-width:1.3px,color:#2a0b3c;
    class A,B,E,G,I,J,K,L,C1,F1 step;
    class C,D,H,F gate;
    class M obs;
```

A few things are worth understanding about this flow.

The introspection surfaces (`/_osproxy/metrics`, `/_osproxy/explain/*`,
`/_osproxy/breakglass`, `/_osproxy/admin/directives`) short-circuit before
authentication, and each is individually gated: `/_osproxy/explain` and
`/_osproxy/breakglass` can be turned off entirely in production
(`osproxy.debug-endpoints`), while `/_osproxy/metrics` always stays on. See
[Observability](/osproxy-java/08-observability/).

The TLS gate is a hard rule when `osproxy.require-tls-for-mutation` is set:
a body-mutating request over cleartext is refused with `401` before any work
happens. You cannot rewrite an encrypted stream, so the proxy has to
terminate TLS to do tenancy at all.

Credentials are consumed at the edge. `BearerAuth` reads the client
`Authorization` header and resolves a `Principal`; the pipeline never sees
the raw token.

The passthrough check runs before tenancy resolution, not after: a request
whose logical index matches the configured `PassthroughPolicy` skips
resolution, the write gate, and both body transforms entirely, forwarding
verbatim through `Reader.forward(...)`. Unmatched requests fall through to
ordinary tenancy (fail-closed).

Resolution is partition-first. `TenancyRouter` turns the request into a
`RouteDecision` (partition, placement, target) plus a body transform. The
engine needs the partition (not just a routing decision) to construct ids
and demux bulk. During a migration the write gate re-checks the epoch at
dispatch and rejects a write that resolved against a now-stale placement as
a retryable `409`.

Around all of it, `Observability` records a shape-only `ExplainDoc` for
every request, success or failure.

## The two body transforms

```mermaid
%%{init: {'theme':'base','themeVariables':{'primaryColor':'#e6f4ea','primaryTextColor':'#0b1f33','primaryBorderColor':'#188038','lineColor':'#5f6368','fontSize':'13px'}}}%%
flowchart LR
    subgraph W["Ingest (shared index)"]
        direction TB
        w1["client body<br/><code>{tenant_id:'acme', amount:42}</code>"]
        w2["inject partition field<br/><code>_tenant:'acme'</code>"]
        w3["construct id<br/><code>acme:1</code> + routing <code>acme</code>"]
        w1 --> w2 --> w3
    end
    subgraph R["Search (shared index)"]
        direction TB
        r1["client query<br/><code>{match:{amount:42}}</code>"]
        r2["wrap: bool.filter<br/><code>{term:{_tenant:'acme'}}</code>"]
        r3["strip <code>_tenant</code> from hits"]
        r1 --> r2 --> r3
    end

    classDef step fill:#e6f4ea,stroke:#188038,stroke-width:1.3px,color:#0b1f33;
    class w1,w2,w3,r1,r2,r3 step;
```

The partition filter is a **structural enclosure**: your query becomes the
`must` clause inside a `bool` that the proxy controls, with the partition
`term` as a mandatory `filter`. A client cannot remove or escape it
(NFR-S4). For shared-index placements the partition id is also mandatory in
the document id template, so by-id reads and writes can't collide across
tenants. `TenancyRouter` fails closed if a shared-index placement lacks a
partition-scoped id rule.

## Placement kinds

```mermaid
%%{init: {'theme':'base','themeVariables':{'primaryColor':'#e8f0fe','primaryTextColor':'#0b1f33','primaryBorderColor':'#1a73e8','lineColor':'#5f6368','fontSize':'13px'}}}%%
flowchart TB
    P["partition (tenant)"] --> Q{"placement kind"}
    Q --> S["SharedIndex<br/>cluster + index + inject<br/>isolate by field + filter + id"]
    Q --> D["DedicatedIndex<br/>cluster + physical index<br/>isolate by index"]
    Q --> C["DedicatedCluster<br/>cluster, logical index kept<br/>isolate by cluster"]

    classDef step fill:#e8f0fe,stroke:#1a73e8,stroke-width:1.3px,color:#0b1f33;
    class P,S,D,C step;
    classDef dec fill:#fef7e0,stroke:#f9ab00,stroke-width:1.3px,color:#3c2a00;
    class Q dec;
```

`Placement` is a Java sealed interface with these three records as
permitted subtypes; the compiler enforces the switch over placement kinds
is exhaustive at every call site.

## Configuration model

Configuration is typed (`ProxyConfig`, a record) and **fully validated at
startup, before any socket opens**: a bad value is a `ConfigException`
naming the field. It loads from Helidon `Config` (a YAML/properties file
merged with `OSPROXY_*` environment overrides). Live, fleet-wide changes
(the placement table, diagnostics directives) flow through a **polling
control plane** at runtime instead, see
[Configuration](/osproxy-java/07-configuration/) and
[Observability & Control Plane](/osproxy-java/08-observability/).

## Ingress protocols

REST over HTTP/1.1 and HTTP/2 share one `HttpRouting`, one port, and one TLS
configuration: adding `helidon-webserver-http2` to the classpath is enough for
Helidon to negotiate HTTP/2 automatically, both `h2c` (cleartext, prior
knowledge or an HTTP/1.1 upgrade) and `h2` (ALPN over TLS), alongside HTTP/1.1
on the exact same listener. `AppHandler`'s routing is written once, against
the protocol-agnostic `HttpRouting.Builder#any`, so nothing about the request
pipeline changes across protocol versions.

A gRPC `DocumentService` (one `Index` RPC, mirroring the Rust sibling's own
gRPC surface) rides the same port on its own `GrpcRouting`, since gRPC is
itself just another protocol layered on the same HTTP/2 connection. Every RPC
is adapted into the same `RequestCtx` the REST path builds and driven through
the identical `AppHandler#dispatch`, so tenancy, isolation, and observability
are unchanged across protocols; only the wire envelope differs. Bearer-token
auth reads the token off the gRPC call's `authorization` metadata the same
way REST reads it off the `Authorization` header.

## What's different from the Rust `osproxy`

This is a from-scratch port, not a line-for-line translation, and a few
things differ by platform or by scope:

- **gRPC listener**: shares the REST port and TLS configuration (see
  [Ingress protocols](#ingress-protocols) above); the Rust sibling instead
  binds gRPC on its own optional `grpc_bind` address.
- **Authorization**: no separate post-authentication `Authorizer` seam yet.
  `BearerAuth` resolves a `Principal`, and that's the extent of the
  built-in auth model.
- **Streaming request/response bodies**: closed for every endpoint that
  reads a request body. Passthrough streams both directions verbatim, not
  bound by `osproxy.max-body-bytes` at all. Tenanted `_bulk` parses and
  dispatches one NDJSON item at a time, also unbound by the cap. Single-doc
  ingest (`_doc`/`_create`) and search/count all stream via token-level
  JSON transforms (`Fields.injectFieldsStreaming`,
  `Queries.wrapQueryStreaming`) that copy the client's body straight into
  the upstream request without materializing it as a byte[] or a full
  Jackson tree, but both of these **keep enforcing**
  `osproxy.max-body-bytes`: that cap is a pre-existing resource-protection
  guarantee for one request's body specifically, and streaming makes it
  possible to *keep* enforcing it without the buffering cost, not a reason
  to drop it (see [Choosing a Mode](/osproxy-java/10-choosing-a-mode/)).
  Search's transform still reads one subtree (`aggs`/`aggregations`) as a
  tree rather than streaming it, since the unfilterable check
  (`global` aggregations) needs to see the whole clause before deciding to
  refuse, a real structural requirement, not unported effort; every other
  field streams. Scroll-open and PIT search stay on the buffered path (they
  need the body for the cursor lifecycle itself), as does async write mode
  (the queued envelope needs a complete body).
- **etcd-backed control plane**: the Rust project has a reference
  `EtcdDirectiveStore`; the Java port uses HTTP-polling stores
  (`PollingDirectiveStore`/`PollingPlacementStore`) against any HTTP source
  instead, which is simpler to operate but polls rather than watches.

→ [Components (Module View)](/osproxy-java/04-components/)
