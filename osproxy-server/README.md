# osproxy-server

The executable: `Main` wires every module together from a `ProxyConfig`
and starts a Helidon SE `WebServer` on virtual threads. `AppHandler` is the
REST ingress (HTTP/1.1 and HTTP/2, negotiated automatically on the same
port); `GrpcDocumentService`/`GrpcMetadataInterceptor` are the gRPC
ingress, sharing that same port and TLS configuration. `BearerAuth`
authenticates clients, `ReferenceTenancy` is the reference `TenancySpi`
implementation (shared index, tenant from the bearer token),
`PollingDirectiveStore`/`PollingPlacementStore` are the reference
distributed control-plane implementations, and `CryptoPosture` engages the
FIPS-approved crypto provider when configured.

This is the only module allowed to depend on all the others, and it hosts
the ArchUnit test that enforces the whole downward-only module dependency
graph.

## Depends on

Everything: `osproxy-core`, `osproxy-spi`, `osproxy-tenancy`,
`osproxy-sink`, `osproxy-engine`, `osproxy-config`, `osproxy-observe`,
`osproxy-capture`, `osproxy-otlp`, `osproxy-kafka`, `osproxy-rewrite`.

## Key types

- `Main`: config → wiring → serve; deliberately one linear method, not a
  framework you extend. `Main.start(ProxyConfig)` is what tests call too.
- `AppHandler`: the Helidon ingress (authenticate → classify → pipeline →
  respond), streaming where the pipeline allows it.
- `GrpcDocumentService`: the gRPC `DocumentService.Index` RPC, adapted
  into the same `RequestCtx`/`AppHandler#dispatch` the REST path uses.
- `BearerAuth`: token → tenant, constant-time comparison, dev mode when
  no tokens are configured.
- `ReferenceTenancy`: the reference `TenancySpi` (shared index, tenant
  from the bearer principal).
- `PollingDirectiveStore` / `PollingPlacementStore`: HTTP-polling
  distributed control-plane stores.
- `CryptoPosture`: FIPS crypto-provider engagement and approved
  TLS suite/protocol pinning.

Run it: `./gradlew :osproxy-server:run`. See
[Wiring It Together](../site/src/content/docs/06-wiring-example.md) and
[Configuration](../site/src/content/docs/07-configuration.md).
