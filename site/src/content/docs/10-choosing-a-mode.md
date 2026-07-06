---
title: "10. Choosing a Mode"
---
One binary, one config, several jobs. This page walks through the five
concrete things osproxy-java is used for, in the order most people reach
for them. If you already know which one you want, jump straight to it.
The layering rules (why some knobs are build-time and others are
per-request) come after, as a reference once you're wiring things up.

## 1. Multi-tenant routing

This is the default and needs no extra configuration. Each tenant
addresses a logical index like `orders`; you implement `TenancySpi` to
say where each tenant's data actually lives (a shared index with an
injected isolation field, a dedicated index on a shared cluster, or a
dedicated cluster of its own), and the proxy handles the rewrite on both
directions: inject and construct the id on write, filter and strip on
read. A client cannot see another tenant's data and cannot craft a query
that escapes its own partition filter.

Read [The SPI](/osproxy-java/05-spi-guide/) to implement `TenancySpi`, and
[Wiring It Together](/osproxy-java/06-wiring-example/) for a working
example.

## 2. Tenant-agnostic routing

Sometimes you want osproxy-java as a plain reverse proxy: auth, TLS,
pooling, and observability at one boundary, with no partition resolution
and no body rewrite. Set `osproxy.passthrough-cluster` and
`osproxy.passthrough-endpoint` and every request forwards verbatim.

You can also run both at once. Add `osproxy.passthrough-indices` (a
comma-separated list of logical-index prefixes) and only those indices
pass through untouched; every other index stays fully tenant-isolated, on
the same instance. This is the shape you want while onboarding: a legacy
index nobody has migrated yet flows through as-is next to indices you've
already brought under tenancy, no second deployment needed. The match is
index-prefix based, evaluated before tenancy resolution and before any
body is read, and fail-closed: an index that doesn't match a configured
prefix keeps full tenancy, never the other way around, so a client can't
opt itself out of isolation by naming an index cleverly.

A passthrough-matched request also streams both directions: the client
body goes straight to the upstream and the response straight back, so
`osproxy.max-body-bytes` never applies to it at all. See
[Performance](/osproxy-java/11-performance/) for a measured proof, a 16
MiB body through a proxy configured with a 1 KiB cap.

## 3. Traffic capture for telemetry and debugging

The `Capture` seam (`osproxy-capture`) tees every request and response,
raw, to wherever you point it, typically Kafka via `osproxy-kafka`,
independent of whether that traffic is tenanted or passed through. This
is for replay, audit, or debugging something a shape-only trace can't
explain: you get the actual bodies, not just their shape.

There's no config key for this; you wire it in your own `main` because
what backend to send captures to is a real architectural choice, not a
toggle:

```java
AppHandler handler = new AppHandler(pipeline, auth)
        .withCapture(Capture.redacting(myKafkaBackedCapture));
```

Wrap your implementation in `Capture.redacting(...)` unless you want
`Authorization` headers landing in the capture stream verbatim. Capture
is off unless you call `withCapture`, and it never fails a request: a
broken capture backend degrades telemetry, not availability.

## 4. Zero-downtime cluster migration

Moving a tenant's data to a new cluster or index (a version upgrade, a
capacity rebalance, a blue/green cutover) without corrupting or losing
writes in flight is the other reason osproxy-java exists. Every placement
carries an epoch; a write is only admitted if it resolved against the
placement's current epoch, so a write that was routed a moment before a
cutover gets refused rather than landing on the wrong side of it.

There are two ways to move a partition, for two different levels of
ceremony:

- **A simple flip.** `PollingPlacementStore` polls a placement document
  from any HTTP source and applies changes to the live placement table.
  Re-polling an unchanged document never bumps anything; an actual change
  bumps the epoch, and in-flight writes still routed under the old epoch
  are refused from that instant. Good for a placement change that doesn't
  need a guaranteed no-write window, just a clean cutover point.
- **An orchestrated migration.** `MigrationControl` gives you an explicit
  `SETTLED → DRAINING → CUTOVER → SETTLED` state machine. `DRAINING`
  keeps writes flowing at the current epoch while you copy data
  underneath (external reindex or snapshot tooling does the actual copy;
  osproxy-java only gates the routing). `CUTOVER` flips the placement and
  refuses every write, at any epoch, until you call `complete()`, giving
  you a real, bounded no-write window for the moment data ownership
  actually moves. Reads are never gated by any of this: a read during
  `CUTOVER` goes wherever the placement currently points.

Both paths are library primitives you drive from your own migration
runbook or automation, not an HTTP admin endpoint in the reference
server. `osproxy.passthrough-indices` (use case 2) is often the other
half of a real migration: bring the new index under tenancy while the
still-being-migrated one keeps flowing through untouched.

## 5. Compliance mode: FIPS vs. non-FIPS

`osproxy.fips=true` decides whether the JVM engages the bundled
BouncyCastle FIPS 2.1 provider in approved-only mode at startup. The
provider itself is always on the classpath; the flag is what makes it
matter, so treat it as a build-time posture even though it reads like a
config key. The TLS listener is pinned to an approved cipher and protocol
set (TLS 1.2/1.3, AES-GCM only) regardless of whether `fips` is set. FIPS
mode adds the CMVP-validated provider underneath an already-conservative
default; it doesn't loosen anything when off, and it fails boot loudly if
the module's self-tests refuse.

## Combining modes

These aren't mutually exclusive. Capture works the same whether traffic
is tenanted or passed through. A migration runs inside multi-tenant mode.
FIPS is orthogonal to all of it, a build choice that doesn't change what
any of the other four do. Pick whichever combination your deployment
needs; none of them require the others.

## Where each knob lives

Every knob above lives at exactly one layer, chosen deliberately by blast
radius: the riskier the change, the further from runtime it sits.

| Layer | Examples | Why here |
|-------|----------|----------|
| **Build** | FIPS crypto provider (BC-FIPS vs. the default JCE) | Changes what's linked into the artifact; not something you'd want a runtime toggle to silently flip. |
| **Config** (startup) | Passthrough cluster/indices, header-forwarding policy, async fan-out broker | Deliberate per-deployment choices, validated once at boot; a bad value never boots a proxy. |
| **Per-request** | Async write mode (`x-osproxy-write-mode: async`) | The client's choice for that one call, not a fleet stance. |
| **Runtime, fleet-wide** | Diagnostics directives (verbosity, break-glass, sampling) | The one thing that must change without a restart. You're debugging *because* something is on fire. |

Migration and capture don't fit this table as config keys because they
aren't config keys: migration is driven by calling `MigrationControl`
from your own code, and capture is wired by calling `withCapture` in your
own `main`. Both are deliberate, not accidental; that's consistent with
everything else on this page, just expressed as a method call instead of
a property.

## Sync vs async writes

Synchronous is the default and the only mode search/bulk/multi-op
endpoints support. Async is opt-in per request, single-doc only, and
requires `osproxy.fanout.bootstrap-servers` to be configured. See
[Async Fan-out Writes](/osproxy-java/09-async-clients/).

## Why not one big "make it all dynamic" switch?

Because the blast radius differs by orders of magnitude. A directive
mis-published fleet-wide expires on its own TTL and only ever *raises*
verbosity (shape-only, never a value leak); a mistake here is annoying,
not dangerous. A tenancy mode mis-toggled at runtime could route a write
to the wrong cluster mid-flight. So only the observability surface gets
the runtime-fleet-wide treatment; everything riskier is a deliberate,
validated, restart-time (or explicit-method-call) decision.

→ [Performance](/osproxy-java/11-performance/)
