# osproxy-tenancy

Adapts a user's `TenancySpi` into the routing decision the engine consumes:
`TenancyRouter` resolves a partition, looks up its placement, and fails
closed if a `SharedIndex` placement's `docIdRule` doesn't reference
`{partition}` (two tenants' documents could otherwise collide on one
physical id). `PlacementTable` and `PartitionResolver` back a live,
pollable placement source; `MigrationControl` and `MigrationGatedTenancy`
implement the epoch-gated `SETTLED → DRAINING → CUTOVER → SETTLED` state
machine for a zero-downtime cluster migration.

## Depends on

- `osproxy-core`
- `osproxy-spi`
- `osproxy-rewrite` (implementation only, not exposed on this module's API)

## Key types

- `TenancyRouter`: the `TenancySpi` → routing-decision adapter every
  request goes through; owns the fail-closed `SharedIndex` check.
- `PlacementTable`: a live, mutable placement source (used by the
  reference server's `PollingPlacementStore`).
- `PartitionResolver`: resolves a partition key from a `RequestCtx` per
  the SPI's declared `PartitionKeySpec`.
- `MigrationControl`: the explicit migration state machine
  (`beginDrain`/`cutover`/`complete`), per-partition locked.
- `MigrationGatedTenancy`: wraps a `TenancySpi`, gating writes on the
  migration state above.

See [Architecture](../site/src/content/docs/03-architecture.md) and
[Choosing a Mode](../site/src/content/docs/10-choosing-a-mode.md) (the
zero-downtime migration section).
