---
title: "10. Choosing a Mode"
---
## The map

Every "mode" in osproxy-java lives at exactly one layer, chosen
deliberately by blast radius: the riskier the change, the further from
runtime it sits.

| Layer | Examples | Why here |
|-------|----------|----------|
| **Build** | FIPS crypto provider (BC-FIPS vs. the default JCE) | Changes what's linked into the artifact; not something you'd want a runtime toggle to silently flip. |
| **Config** (startup) | Passthrough cluster/indices, header-forwarding policy, capture, async fan-out broker | Deliberate per-deployment choices, validated once at boot; a bad value never boots a proxy. |
| **Per-request** | Async write mode (`x-osproxy-write-mode: async`) | The client's choice for that one call, not a fleet stance. |
| **Runtime, fleet-wide** | Diagnostics directives (verbosity, break-glass, sampling) | The one thing that must change without a restart — you're debugging *because* something is on fire. |

## Tenanted vs tenant-agnostic (and both at once)

Tenancy is the default. Set `osproxy.passthrough-cluster` +
`osproxy.passthrough-endpoint` to run one instance as a plain reverse
proxy instead — no partition resolution, no body rewrite, no isolation, just
your auth/TLS/pooling/observability. Add `osproxy.passthrough-indices` to
run *both at once*: legacy indices pass through untouched while onboarded
indices stay tenant-isolated, on the same instance. The match is
index-prefix based, evaluated before tenancy resolution
(`AppHandler.handle`'s first check, before any body is read), and
fail-closed — an index that doesn't match a configured prefix keeps full
tenancy, never the other way around.

A passthrough-matched request also **streams both directions**: the client
body is piped straight to the upstream and the response piped straight back,
so it is not bound by `osproxy.max-body-bytes` at all — see
[Performance](/osproxy-java/11-performance/) for a measured proof (a 16 MiB
body through a 1 KiB-capped proxy). Tenanted `_bulk` streams too, despite
still running tenancy resolution and the per-item transform: it parses and
dispatches one NDJSON item at a time instead of buffering the whole payload,
so it escapes the cap on the same terms as passthrough (proof also on the
Performance page).

Single-doc ingest streams as well, when eligible — the physical target and
id have to be derivable from the request alone (no `PartitionKeySpec.BodyField`
partition key), which the reference tenancy satisfies. Unlike passthrough
and `_bulk`, ingest keeps enforcing `osproxy.max-body-bytes`: that cap
protects against one oversized document specifically, and streaming here
is about dropping the *buffering cost* up to the cap, not the cap itself.
Search still buffers fully, since wrapping the client's query needs the
whole top-level object to check for unfilterable constructs first.

## Sync vs async writes

Synchronous is the default and the only mode search/bulk/multi-op
endpoints support. Async is opt-in per request, single-doc only, and
requires `osproxy.fanout.bootstrap-servers` to be configured — see
[Async Fan-out Writes](/osproxy-java/09-async-clients/).

## Capture on/off

`osproxy.capture` config wires a `Capture` implementation (typically
Kafka-backed via `osproxy-kafka`) that tees every request/response,
independent of whether that request is tenanted or passed through. Off by
default; when on, wrap it in `Capture.redacting(...)` unless you want
`Authorization` headers landing in the capture stream verbatim.

## FIPS

`osproxy.fips=true` is a config flag, but what it engages is a build-time
concern in spirit: BouncyCastle FIPS 2.1 is always on the classpath (bundled
from Maven Central), and the flag decides whether the JVM engages it in
approved-only mode at startup. The TLS listener is pinned to an approved
cipher/protocol set (TLS 1.2/1.3, AES-GCM only) **regardless** of whether
`fips` is set — FIPS mode adds the CMVP-validated provider underneath an
already-conservative default, it doesn't loosen anything when off.

## Why not one big "make it all dynamic" switch?

Because the blast radius differs by orders of magnitude. A directive
mis-published fleet-wide expires on its own TTL and only ever *raises*
verbosity (shape-only, never a value leak) — a mistake here is annoying,
not dangerous. A tenancy mode mis-toggled at runtime could route a write to
the wrong cluster mid-flight. So only the observability surface gets the
runtime-fleet-wide treatment; everything riskier is a deliberate,
validated, restart-time decision.

→ [Performance](/osproxy-java/11-performance/)
