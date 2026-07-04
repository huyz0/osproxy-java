---
title: "5. The SPI"
---
`osproxy-spi` is the narrow waist: the interfaces and value types you write
against. Everything below it (`osproxy-core`) is pure vocabulary; everything
above it is the proxy's own machinery, which you never subclass.

## `TenancySpi`: the one you almost always implement

```java
public interface TenancySpi {
    PartitionKeySpec partitionKeySpec();
    PlacementAt placementFor(PartitionId partition) throws SpiException;
    default Optional<DocIdRule> docIdRule() { return Optional.empty(); }
    default List<InjectedField> injectedFields() { return List.of(); }
    default boolean admitWrite(PartitionId partition, Epoch epoch) { return true; }
    default Optional<String> clusterEndpoint(ClusterId cluster) { return Optional.empty(); }
}
```

Two methods are required; the rest default to "no tenancy behavior," so a
minimal implementation is a handful of lines.

### Invariants you must uphold

- **Never let an unchecked exception escape.** Every anticipated failure is
  a typed `SpiException` subtype (the analog of the Rust contract's "must
  not panic"). `placementFor` may block on a backend lookup — methods run
  on virtual threads, so blocking I/O here is fine and idiomatic.
- **A `SharedIndex` placement's `docIdRule` must reference `{partition}`.**
  `TenancyRouter` fails closed (refuses the request) if it doesn't — two
  tenants' documents could otherwise collide on the same physical id.
- **`injectedFields()` and the read-side filter are symmetric by
  construction.** You don't write the read filter yourself; the engine
  derives it from the same `InjectedField` list you declare for writes.

### Partition key sources

`PartitionKeySpec` is a sealed interface: a dotted body-field path
(`BodyField("customer.tenant")`), a request header (`Header("x-tenant")`),
a principal attribute (`PrincipalAttr("tenant")`), or `AnyOf(...)` trying
several sources in order. The reference `ReferenceTenancy` in
`osproxy-server` uses `PrincipalAttr("tenant")`, since `BearerAuth` already
resolves the tenant from the bearer token.

### Deriving the partition with code

```java
public final class MyTenancy implements TenancySpi {
    private final ClusterId cluster;
    private final IndexName sharedIndex;

    @Override
    public PartitionKeySpec partitionKeySpec() {
        return new PartitionKeySpec.PrincipalAttr("tenant");
    }

    @Override
    public PlacementAt placementFor(PartitionId partition) {
        return new PlacementAt(
                new Placement.SharedIndex(cluster, sharedIndex,
                        List.of(new InjectedField("_tenant", InjectedValue.PartitionIdValue.INSTANCE))),
                Epoch.INITIAL);
    }

    @Override
    public Optional<DocIdRule> docIdRule() {
        return Optional.of(new DocIdRule("{partition}:{id}", true));
    }
}
```

### Telling the proxy where clusters live

Most sinks (including `OpenSearchSink`) are handed a static
`Map<ClusterId, String>` of endpoints at construction. Override
`clusterEndpoint` only if your placement backend also tracks endpoints
dynamically (the polling placement store does this, see
[Configuration](/osproxy-java/07-configuration/)).

## `BearerAuth` (built-in)

Unlike the Rust project's pluggable `Authenticator`/`Authorizer` traits,
osproxy-java ships one concrete `BearerAuth`: a `Map<String, String>` of
bearer token → tenant. An empty map is "dev mode" (the `x-tenant` header is
trusted — never run that in production). There is no extension seam here
yet; if you need a different auth model, the fork point is `AppHandler`
itself, which is a small, readable class.

## `Sink` / `Reader` (optional): swap the backend

```java
public interface Sink {
    WriteBatch.Ack write(List<WriteBatch.Op> ops) throws SinkException;
}

public interface Reader {
    Response get(Target target, String physicalId, Optional<String> routing) throws SinkException;
    Response search(Target target, byte[] body) throws SinkException;
    Response count(Target target, byte[] body) throws SinkException;
    // scroll/PIT/forward have default "unsupported" implementations
}
```

`OpenSearchSink` implements both over a pooled Helidon `WebClient`.
`MemorySink` implements both in memory for tests. Implement your own pair to
back the proxy with something else entirely — the engine only calls
through these two interfaces, never the concrete OpenSearch client.

## `Router` (advanced): custom routing that bypasses tenancy

`TenancyRouter` adapts a `TenancySpi` into the engine's `Router` seam. Most
users never touch `Router` directly. The one built-in exception is
`PassthroughPolicy`, which the `Pipeline` checks *before* asking the router
anything at all — see [Choosing a Mode](/osproxy-java/10-choosing-a-mode/).

## Error taxonomy

`SpiException` is a sealed abstract class; every subtype carries a stable
`ErrorCode` the engine maps to an HTTP status and a shape-only wire body.
The variants mirror the Rust `SpiError` enum: `PartitionUnresolved`,
`PlacementMissing`, `PlacementBackend` (retryable/terminal), and others.
Because the hierarchy is sealed, the engine's `catch` block and any
exhaustive `switch` over it are checked complete by the compiler.

→ [Wiring It Together](/osproxy-java/06-wiring-example/)
