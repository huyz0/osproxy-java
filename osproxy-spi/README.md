# osproxy-spi

The public SPI a user implements: `TenancySpi` (partition key location,
placement lookup, doc-id rule, injected fields, migration write gate,
routing hint) and the value types it hands back and forth with the engine
(`RequestCtx`, `Placement`, `PlacementAt`, `DocIdRule`, `InjectedField`,
`RouteDecision`, `BodyTransform`), plus the sealed `SpiException` error
taxonomy.

This is the narrow waist of the whole project: everything below it
(`osproxy-core`) is pure vocabulary, everything above it is the proxy's own
machinery, which you never subclass. A consumer's own code depends on this
module and implements `TenancySpi`; it never needs to touch `osproxy-engine`
or `osproxy-tenancy` directly.

## Depends on

- `osproxy-core`

## Key types

- `TenancySpi`: the interface you implement. `partitionKeySpec()` and
  `placementFor()` are required, the rest (`docIdRule`, `injectedFields`,
  `admitWrite`, `clusterEndpoint`, `routingHint`) default to "no tenancy
  behavior."
- `RequestCtx`: the read-only, authenticated, classified request view
  handed to the SPI and the pipeline.
- `Placement` (sealed): `DedicatedCluster` / `DedicatedIndex` /
  `SharedIndex`, the three ways a partition can physically live.
- `DocIdRule`: the physical-id template (`{partition}:{id}`) and whether
  to set OpenSearch `routing`.
- `SpiException` (sealed): every anticipated SPI failure, each carrying a
  stable `ErrorCode`; implementations must never throw unchecked.

See [The SPI](../site/src/content/docs/05-spi-guide.md) and
[Wiring It Together](../site/src/content/docs/06-wiring-example.md).
