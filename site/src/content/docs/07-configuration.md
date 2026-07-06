---
title: "7. Configuration"
---
## Sources and precedence

`ProxyConfig.load(Config)` reads a Helidon `Config` tree rooted at
`osproxy`, which Helidon itself merges from (in increasing precedence) a
config file, then `OSPROXY_*` environment variables (Helidon's standard
env-var mapping: `osproxy.max-body-bytes` ↔ `OSPROXY_MAX_BODY_BYTES`).
Validation happens once, at construction, a bad value is a
`ProxyConfig.ConfigException` naming the field, and the process never binds
a socket.

For programmatic construction (tests, embedding), prefer
`ProxyConfig.builder(port, upstream, index)...build()` over the telescoping
constructors, it names every field, so a new field can't silently shift
the meaning of an existing positional call.

## Settings reference

### Networking

| Key | Default | Meaning |
|-----|---------|---------|
| `osproxy.port` | `9200` | Ingress port (`0` = ephemeral, for tests). |
| `osproxy.upstream` | *(required)* | The one upstream cluster's base URL. |
| `osproxy.index` | *(required)* | The shared physical index of the reference tenancy. |
| `osproxy.max-body-bytes` | 32 MiB | Request-body cap; larger requests are refused with 413 before buffering, even over chunked transfer-encoding. |

### Authentication & TLS

| Key | Default | Meaning |
|-----|---------|---------|
| `osproxy.tokens.<token>` | *(none)* | Bearer token → tenant. Empty map means dev mode (`x-tenant` header trusted); never run that in production. |
| `osproxy.require-tls-for-mutation` | `false` | Refuse body-mutating requests over cleartext (NFR-S1); requires `tls` to be configured. |
| `osproxy.tls.cert-path` / `.key-path` / `.client-ca-path` | *(none)* | TLS listener; setting `client-ca-path` requires and verifies client certificates (mTLS). |

### Observability & diagnostics

| Key | Default | Meaning |
|-----|---------|---------|
| `osproxy.log-requests` | `false` | Emit one shape-only JSON line per request to stdout; also raises the observability baseline to `VERBOSE`. |
| `osproxy.debug-endpoints` | `true` | Serve `/_osproxy/explain` and `/_osproxy/breakglass`; `false` in production reports `not_enabled` instead. `/_osproxy/metrics` always stays on. |
| `osproxy.log-diagnostic-captures` | `false` | Also push each `ring_buffer`-selected explain doc to stdout as a tagged JSON line, the fleet-coherent counterpart of the local break-glass ring. |
| `osproxy.tenant-metrics-enabled` | `false` | Serve bounded per-tenant request/failure/latency counters at `/_osproxy/metrics/tenants` (Prometheus text), gated by `debug-endpoints` like `explain`/`breakglass`. Also pushed to `otlp-endpoint` as OTLP metrics when both are set. See [Observability](/osproxy-java/08-observability/). |
| `osproxy.tenant-metrics-export-interval-seconds` | `15` | How often a tenant-metrics snapshot is pushed to `otlp-endpoint`; only relevant when both `tenant-metrics-enabled` and `otlp-endpoint` are set. |
| `osproxy.directive-admin-token` | *(none)* | Bearer token gating `POST/GET /_osproxy/admin/directives`; absent means the endpoint does not exist. |
| `osproxy.otlp-endpoint` | *(none)* | OTLP collector base URL; absent means no span export. |
| `osproxy.service-name` | `osproxy` | Service name attached to exported spans. |

### Control plane & routing

| Key | Default | Meaning |
|-----|---------|---------|
| `osproxy.directives-url` | *(none)* | HTTP source polled for the fleet diagnostics directive set; absent means directives are instance-local (only the admin endpoint publishes). |
| `osproxy.directives-poll-seconds` | `10` | Poll interval for `directives-url`. |
| `osproxy.placements-url` | *(none)* | HTTP source polled for fleet placements; absent means every partition stays on the reference shared index. |
| `osproxy.placements-poll-seconds` | `10` | Poll interval for `placements-url`. |
| `osproxy.passthrough-cluster` / `.passthrough-endpoint` | *(none)* | Set both (or neither) to enable tenant-agnostic passthrough to one cluster. |
| `osproxy.passthrough-indices` | *(empty)* | Comma-separated logical-index prefixes; empty means every request passes through once cluster/endpoint are set. |
| `osproxy.header-forwarding.enabled` | `true` | Forward client headers to the upstream on every sink call (write/read/cursor/passthrough), minus the mandatory hop-by-hop/framing set. |
| `osproxy.header-forwarding.deny` | *(empty)* | Comma-separated extra headers to drop from the forwarded set (case-insensitive), e.g. `authorization`. |
| `osproxy.cursor-affinity-key` | *(none)* | HMAC key sealing scroll/PIT cursor affinity; absent refuses the cursor endpoints fail-closed. |
| `osproxy.fips` | `false` | Engage the bundled BouncyCastle FIPS provider in approved-only mode; fails boot loudly if the module's self-tests refuse. |
| `osproxy.delete-by-query-expansion` | `false` | Opt into the `_delete_by_query` async expansion (only takes effect when async write mode is itself available; see [Async Fan-out Writes](/osproxy-java/09-async-clients/)). |
| `osproxy.admin-cluster` / `.admin-endpoint` | *(none)* | The cluster (+ optional base URL) `_cat`/`_cluster`/`_nodes` requests forward to when allow-listed; absent means admin is always refused. |
| `osproxy.admin-allowed-prefixes` | *(empty)* | Comma-separated path prefixes permitted through (e.g. `/_cat/`); empty allows nothing even with a cluster configured. Admin output is cluster-wide, not tenant-scoped, the allow-list is the only safety boundary. |

### Traffic capture & async fan-out (Kafka)

| Key | Default | Meaning |
|-----|---------|---------|
| `osproxy.fanout.bootstrap-servers` | *(none)* | Kafka bootstrap servers; absent means async write mode (`x-osproxy-write-mode: async`) is refused with 503. |
| `osproxy.fanout.topic` | `osproxy-writes` | Topic async write envelopes land on. |

## Worked examples

### Local development (cleartext, open auth, full debug)

```yaml
osproxy:
  upstream: "http://localhost:9200"
  index: "shared"
  log-requests: true
```

No tokens configured, `x-tenant` is trusted. `debug-endpoints` defaults on.

### Production (mTLS, token auth, diagnostics targeted not blanket)

```yaml
osproxy:
  upstream: "https://opensearch.internal:9200"
  index: "shared"
  tokens:
    "${TOKEN_ACME}": "acme"
  require-tls-for-mutation: true
  tls:
    cert-path: "/etc/osproxy/tls/cert.pem"
    key-path: "/etc/osproxy/tls/key.pem"
    client-ca-path: "/etc/osproxy/tls/client-ca.pem"
  debug-endpoints: false
  directive-admin-token: "${DIRECTIVE_ADMIN_TOKEN}"
  fips: true
```

`debug-endpoints: false` keeps `/_osproxy/explain` and `/_osproxy/breakglass`
off by default; a targeted diagnostics directive published through
`/_osproxy/admin/directives` (which stays token-gated regardless) raises
verbosity for a specific tenant/index/principal/endpoint on demand.

### Environment-variable form

```sh
export OSPROXY_UPSTREAM=http://localhost:9200
export OSPROXY_INDEX=shared
export OSPROXY_REQUIRE_TLS_FOR_MUTATION=true
```

## What is *not* configured here

The placement table for anything beyond the reference shared-index tenancy,
and the diagnostics directive set beyond the process-startup baseline, are
**runtime control-plane state**, not config-file state, they flow through
`PollingPlacementStore`/`PollingDirectiveStore` (or the admin endpoint) so a
fleet-wide change never needs a restart. See
[Observability & Control Plane](/osproxy-java/08-observability/).

→ [Observability & Control Plane](/osproxy-java/08-observability/)
