# osproxy-capture

Traffic-capture and acked-queue-producer seams: `Capture` (tees a request/
response record somewhere) and `AckProducer` (the durable, acknowledged
enqueue contract async writes use). No broker client here at all, the
default build links nothing broker-related; `osproxy-kafka` supplies the
real `AckProducer`, and a deployer's own `Capture` implementation supplies
wherever captured traffic actually goes.

`Capture.safe(...)` and `Capture.redacting(...)` are composable wrappers:
`safe` guarantees a broken capture backend can never fail the request it's
capturing (a caught `RuntimeException` is dropped, by contract), and
`redacting` strips `Authorization` before a record reaches the backend.

## Depends on

- `osproxy-core`

## Key types

- `Capture`: the tee-a-record interface, plus the `safe`/`redacting`
  static composition helpers and the `Record` value type.
- `AckProducer`: the durable-enqueue contract (`produceAcked`) async
  writes and capture backends both use.
- `MemoryCapture` / `MemoryAckProducer`: in-memory implementations for
  tests.

See [Choosing a Mode](../site/src/content/docs/10-choosing-a-mode.md)
(traffic capture) and
[Async Fan-out Writes](../site/src/content/docs/09-async-clients.md).
