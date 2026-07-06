# osproxy-core

Zero-I/O vocabulary shared by every other module: ids (`ClusterId`,
`IndexName`, `PartitionId`), `EndpointKind`, `Target`, `Epoch`, `ErrorCode`,
a `Clock` seam (`SystemClock`/`ManualClock` for tests), and the per-request
trace/forward-header `ScopedValue` bindings (`Tracing`, `ForwardHeaders`).

Nothing here does I/O, parses JSON, or knows about tenancy, routing, or the
wire protocol. It exists so every module downstream can share the same
newtypes and error taxonomy instead of each redefining "what a cluster id
is."

## Depends on

Nothing: every other module in this project builds on this one.

## Key types

- `ClusterId` / `IndexName` / `PartitionId`: validated single-value
  records, the newtypes every placement decision is built from.
- `EndpointKind`: the classified request kinds (`INGEST_DOC`,
  `INGEST_BULK`, `SEARCH`, `DELETE_BY_QUERY`, ...); classification happens
  once at ingress, everything downstream switches on this.
- `Target`: a resolved (cluster, index) pair a request actually dispatches to.
- `Epoch`: the migration-generation stamp a placement decision is made under.
- `ErrorCode`: the stable wire vocabulary every refusal maps to (status +
  JSON body), shared across REST and gRPC.
- `Tracing` / `ForwardHeaders`: `ScopedValue`s bound once per request by the
  ingress, read at the sink's upstream choke point without threading a
  parameter through every call site.
- `TraceContext`: W3C `traceparent` parse/format/propagate.

See [Architecture](../site/src/content/docs/03-architecture.md) and
[Components](../site/src/content/docs/04-components.md).
