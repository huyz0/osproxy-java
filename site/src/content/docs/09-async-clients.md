---
title: "9. Async Fan-out Writes"
---
## What changes for the client

A single-document write (`INGEST_DOC` or `DELETE_BY_ID`) carrying
`x-osproxy-write-mode: async` is fully transformed by the pipeline exactly
as a synchronous write would be, but instead of dispatching to OpenSearch it
hands the envelope to a Kafka producer and returns as soon as the broker
acknowledges — not as soon as OpenSearch has indexed it.

```
PUT /orders/_doc/42
x-osproxy-write-mode: async
x-tenant: acme

{"amount": 100}
```

```
202 Accepted
{"status":"accepted","op_id":"<broker-assigned offset/id>"}
```

## Refuse, don't lie

Async mode is opt-in per request and narrowly scoped:

- **No fan-out sink configured** (`osproxy.fanout.bootstrap-servers` unset)
  → `503`. The proxy never silently falls back to synchronous.
- **Anything other than a single-doc write** (bulk, search, a multi-op
  request) with the header set → `400`. Async mode has no batching
  semantics; asking for it on the wrong endpoint is a client error, not a
  degraded success.
- **The broker doesn't acknowledge** (timeout, not-enough-replicas) → an
  error, never a `202`. A `202` is a promise the broker actually made; the
  proxy does not fabricate it to look successful.

## Selecting async

Set the header on the request you want async — there is no fleet-wide or
per-tenant default; the client decides per call. This mirrors the Rust
project's ADR-010 stance: async is a request-level choice, not a
deployment-level one, because the durability trade-off (broker ack vs.
cluster-indexed ack) is the client's to make for that particular write.

## Handling the outcome

The response body's `op_id` is the broker-assigned identifier for the
produced record — opaque to the client, useful for correlating with
whatever consumes the topic downstream (a pull-based indexer, an audit
log, a replay tool). There is no synchronous "did it land" callback beyond
the broker ack; if you need to know the document is queryable, poll for it
or consume the same topic.

→ [Choosing a Mode](/osproxy-java/10-choosing-a-mode/)
