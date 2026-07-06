# osproxy-observe

Shape-only observability: request metrics (`Metrics`), the explain-by-id
store (`ExplainStore`/`ExplainDoc`), a bounded break-glass tape
(`BreakGlassBuffer`), and the fleet-wide diagnostics directive model
(`Directive`/`DirectiveSet`/`DiagLevel`). Depends only on `osproxy-core` by
design: this module never sees tenant values, bodies, or partition ids by
construction, only endpoint kinds, statuses, ids, and timings, so the
"shape-only" guarantee is structural, not a convention someone has to
remember to uphold.

## Depends on

- `osproxy-core`

## Key types

- `Metrics`: always-on atomics (request counts, per-cluster pool reuse),
  served by the reference server's `GET /_osproxy/metrics`.
- `TenantMetrics`: opt-in, bounded per-tenant request/failure/latency
  counters (Caffeine-backed, TTL + hard-cap eviction so cardinality stays
  bounded by live tenants, not all-time count). The one deliberate exception
  to this module's shape-only rule; off by default.
- `ExplainDoc` / `ExplainStore`: the shape-only causal trace captured per
  request, retrievable by request id via `GET /_osproxy/explain/{id}`.
- `BreakGlassBuffer`: a bounded ring buffer of recent explain docs,
  captured only when a directive asks for it.
- `DirectiveSet` / `Directive` / `DiagLevel`: the runtime, fleet-wide
  diagnostics model (target by tenant/index/principal/endpoint, sampled,
  TTL-bounded, highest-level-wins).
- `DiagnosticSink`: the seam for pushing a captured explain doc
  off-instance for fleet-coherent break-glass.
- `SpanExporter`: the trace-export seam `osproxy-otlp` implements.

See [Observability & Control Plane](../site/src/content/docs/08-observability.md).
