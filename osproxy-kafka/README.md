# osproxy-kafka

The real `AckProducer` implementation over `kafka-clients`
(`acks=all`, idempotent), used by both async fan-out writes and traffic
capture. This is the one module in the project that links a broker
client; everything else stays broker-free, so a deployment that never
configures `osproxy.fanout.bootstrap-servers` (or a capture backend) never
loads any Kafka code path at all.

## Depends on

- `osproxy-capture` (implements its `AckProducer` seam)

## Key types

- `KafkaAckProducer`: construct with a bootstrap-servers string; blocks
  until the broker acknowledges (`acks=all`), never returns a fake success.

See [Async Fan-out Writes](../site/src/content/docs/09-async-clients.md)
and [Configuration](../site/src/content/docs/07-configuration.md) (the
`osproxy.fanout.*` keys).
