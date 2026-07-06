# osproxy-sink

The write/read destination seam: `Sink` (a batch of epoch-stamped
`WriteBatch.Op`s in, per-op `WriteBatch.OpResult`s out) and `Reader`
(get/search/count/forward), plus the two implementations the rest of the
project runs against: `OpenSearchSink` (pooled Helidon `WebClient` per
cluster, with a circuit breaker) for real traffic, and `MemorySink` (an
in-memory doc store) for every engine-level unit test.

The engine never talks to a concrete OpenSearch client directly; it only
calls through `Sink`/`Reader`, so swapping the backend (a different search
engine, a mock, a recording proxy) means implementing these two interfaces
and nothing else.

## Depends on

- `osproxy-core`
- `osproxy-spi`

## Key types

- `Sink` / `Reader`: the two interfaces the engine calls through; also
  the streaming variants (`writeStreaming`/`forwardStreaming`) the
  ingress uses to avoid buffering large bodies.
- `WriteBatch`: the batch vocabulary (`Op`, `OpResult`, `Ack`).
- `DocOp` (sealed): `Index`/`Create`/`Update`/`Delete`, one already fully
  transformed physical operation.
- `OpenSearchSink`: the real implementation, pooled per cluster, TLS/mTLS
  capable, with a `CircuitBreaker`.
- `MemorySink`: implements both `Sink` and `Reader` in memory; the
  harness every engine unit test runs against.
- `SinkException`: the checked exception every upstream failure maps to,
  carrying a stable `ErrorCode`.

See [Architecture](../site/src/content/docs/03-architecture.md) and
[Configuration](../site/src/content/docs/07-configuration.md) (pooling,
timeouts, circuit breaker settings).
