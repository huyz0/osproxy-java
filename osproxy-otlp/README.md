# osproxy-otlp

OTLP/HTTP JSON span export: `OtlpEncoder` builds the wire shape (one
`SERVER` span, shape-only `osproxy.*` attributes, no tenant values), and
`OtlpHttpExporter` POSTs it to `{endpoint}/v1/traces` over the JDK
`HttpClient`, fire-and-forget. A leaf adapter, nothing depends upward on
it, and it never fails a request: export is hardened with a runtime-Handle
guard, a semaphore that drops the span if saturated rather than blocking,
and a 10-second timeout.

## Depends on

- `osproxy-observe` (implements its `SpanExporter` seam)

## Key types

- `OtlpEncoder`: pure `resource_spans` construction from a `TraceContext`
  and the request's shape-only attributes.
- `OtlpHttpExporter`: the concrete `SpanExporter`; construct with the
  collector endpoint and service name, wire into `Observability` via
  `withExporter`.

See [Observability & Control Plane](../site/src/content/docs/08-observability.md)
(the OTLP export section).
