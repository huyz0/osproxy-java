# osproxy-engine

The pipeline: `Pipeline.handle` runs every classified request through
passthrough-check → resolve → rewrite → dispatch → shape-response, in a
fixed order the compiler and an exhaustive `switch` over `EndpointKind`
enforce. `MultiOps` handles `_bulk`/`_mget`/`_msearch` (demux, dispatch,
remux, sync and async), `AsyncWrites` implements the per-request async
write mode, `Classify` turns a (method, path) into an `EndpointKind`, and
`Transforms`/`Shaping` hold the small glue between the SPI's routing
decision and the rewrite module's pure transforms.

Transport-free by design: this module never touches an `HttpRequest`, a
socket, or TLS. `osproxy-server` owns the HTTP/gRPC ingress; everything
here is testable directly with a `MemorySink` and an injected `TenancySpi`,
no server or container required.

## Depends on

- `osproxy-core`
- `osproxy-spi`
- `osproxy-sink`
- `osproxy-tenancy` (implementation only)
- `osproxy-rewrite` (implementation only)

## Key types

- `Pipeline`: the single entry point (`handle`, plus the streaming twins
  `ingestDocStreaming`/`searchStreaming`/`openBulkStream`).
- `MultiOps`: `_bulk`/`_mget`/`_msearch`, sync and async.
- `AsyncWrites`: the per-request async write mode (transform, enqueue,
  honest `202 {status, op_id}`), refuse-don't-lie everywhere else.
- `Classify`: path/method → `EndpointKind`, once at ingress.
- `PassthroughPolicy` / `AdminPolicy`: the tenant-agnostic and admin
  pass-through opt-ins, checked before tenancy.
- `CursorCodec` / `Cursors`: scroll/PIT lifecycle and HMAC-sealed affinity.
- `PipelineResponse`: what the pipeline hands the transport (status + body).

See [Architecture](../site/src/content/docs/03-architecture.md).
