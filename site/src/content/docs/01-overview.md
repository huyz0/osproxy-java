---
title: "1. Overview & Intent"
---
## The goal in one sentence

Route each OpenSearch request to the correct physical placement based on a
pluggable, partition-based policy, run as a Java 25 library on Helidon SE's
virtual threads, with observability built for fleet-scale debugging.

## The problem

You run OpenSearch for many tenants (customers, teams, regions) and you want
each tenant to address a single logical index like `orders` or `logs`.
Underneath, their data is placed by a policy you control. A tenant might live
in a shared index (many tenants in one index, isolated by an injected field
plus a mandatory query filter), in a dedicated index on a shared cluster, or
on a dedicated cluster of its own.

You also want to move a tenant from one placement to another without losing
or corrupting writes during the cutover, apply auth, TLS, and telemetry
uniformly at one boundary, and debug a routing problem without SSHing into a
box to read code.

osproxy-java is that boundary.

## What it does

Every request routes to exactly one physical placement based on a partition
key. On ingest the proxy injects partition fields and constructs the document
`_id` (and `_routing`); on read it adds a mandatory partition filter and
strips the injected fields back out. The two halves are provably symmetric,
verified by property tests (jqwik), so what goes in transformed comes back out
clean and isolation cannot be bypassed by a client-supplied query.

A single mixed-partition `_bulk` body is split into per-placement
sub-batches, dispatched, and the `items[]` are re-interleaved in the original
order. Connections are pooled on both sides: client keep-alive downstream via
Helidon's virtual-thread server, per-cluster `WebClient` pools with a circuit
breaker upstream.

The proxy authenticates clients (bearer token) and can be extended with a
custom `Authorizer`; the client's `Authorization` is consumed at the edge and
never reaches the upstream or the trace. Partition migrations are gated by an
epoch so a write that resolved against a stale placement is rejected rather
than misrouted. And every request emits a structured, shape-only causal trace,
retrievable via `/_osproxy/explain/{id}`, togglable at runtime across the
fleet without a restart.

```mermaid
%%{init: {'theme':'base','themeVariables':{'primaryColor':'#e6f4ea','primaryTextColor':'#0b1f33','primaryBorderColor':'#188038','lineColor':'#5f6368','fontSize':'14px'}}}%%
flowchart TB
    subgraph ingest["Write path (ingest)"]
        direction LR
        wi["client doc<br/>{tenant_id, …}"] --> wj["inject partition field<br/>construct _id / _routing"] --> wk[("physical write")]
    end
    subgraph read["Read path (search / get)"]
        direction LR
        ri["client query"] --> rj["wrap in mandatory<br/>partition filter"] --> rk["fetch"] --> rl["strip injected<br/>fields from hits"] --> rm["clean logical view"]
    end

    classDef step fill:#e6f4ea,stroke:#188038,stroke-width:1.4px,color:#0b1f33;
    classDef store fill:#fef7e0,stroke:#f9ab00,stroke-width:1.4px,color:#3c2a00;
    class wi,wj,ri,rj,rk,rl,rm step;
    class wk store;
```

## What it does not do

These are deliberate cuts.

| Non-goal | Why, or where it goes |
|----------|---------------------|
| Synchronous fan-out / scatter-gather search | Search is always single-cluster. A partition lives in one place. |
| Cross-cluster result/agg merge, cross-cluster scoring | Removed by single-target search. |
| Synchronous dual/triple-write redundancy | Deferred to the async write mode (`osproxy-kafka`) behind the `Sink` seam. |
| Copying partition data during migration | External reindex/snapshot tooling does the copy; the proxy only gates the routing flip. |
| Dynamic plugin loading | The SPI is compiled in statically, one interface at a time. |
| The proxy mutating cluster state via AI | Observability is read-only. An agent observes; humans or automation act. |

## Who uses it, and how

Five concrete jobs drove the design: multi-tenant routing (the default),
tenant-agnostic routing (plain reverse proxy, or both at once with
staged onboarding), traffic capture for telemetry and debugging,
zero-downtime cluster migration, and a FIPS-compliant build for regulated
environments. [Choosing a Mode](/osproxy-java/10-choosing-a-mode/) walks
through each with the config or code that gets you there.

You consume it by depending on `osproxy-spi`, implementing `TenancySpi` (and
optionally a custom `Sink`/`Reader`, or `Router`), and assembling the
pipeline, handler, and Helidon `WebServer` in `main`. See
[The SPI](/osproxy-java/05-spi-guide/) and
[Wiring It Together](/osproxy-java/06-wiring-example/).

→ [Requirements & NFRs](/osproxy-java/02-requirements-and-nfrs/)
