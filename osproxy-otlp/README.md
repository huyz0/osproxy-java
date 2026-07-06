# osproxy-otlp

OTLP/HTTP JSON export, for both traces and tenant metrics. `OtlpEncoder`
builds one `SERVER` span (shape-only `osproxy.*` attributes, no tenant
values) and `OtlpHttpExporter` POSTs it to `{endpoint}/v1/traces`, fire and
forget, per request. `OtlpMetricsEncoder`/`OtlpHttpMetricsExporter` do the
same for a `TenantMetrics` snapshot, POSTed to `{endpoint}/v1/metrics` as
cumulative `Sum` data points (this is the one encoder in the module that
does carry a tenant value, since that is the whole point of that seam). A
leaf adapter, nothing depends upward on it, and neither exporter ever fails
a request: hardened with a runtime-Handle guard, a semaphore that drops the
payload if saturated rather than blocking, and a 10-second timeout.

## Depends on

- `osproxy-observe` (implements its `SpanExporter` and `MetricsExporter` seams)

## Key types

- `OtlpEncoder`: pure `resource_spans` construction from a `TraceContext`
  and the request's shape-only attributes.
- `OtlpHttpExporter`: the concrete `SpanExporter`; construct with the
  collector endpoint and service name, wire into `Observability` via
  `withExporter`.
- `OtlpMetricsEncoder`: pure `resource_metrics` construction from a
  `TenantMetrics` snapshot (three cumulative sums, one `tenant` attribute).
- `OtlpHttpMetricsExporter`: the concrete `MetricsExporter`; polled
  periodically by `osproxy-server`'s `TenantMetricsExportScheduler`, not
  called per request.

See [Observability & Control Plane](../site/src/content/docs/08-observability.md)
(the OTLP export section).
