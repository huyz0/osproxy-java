---
title: "6. Wiring It Together"
---
## How the pieces fit

```mermaid
%%{init: {'theme':'base','themeVariables':{'primaryColor':'#e8f0fe','primaryTextColor':'#0b1f33','primaryBorderColor':'#1a73e8','lineColor':'#5f6368','fontSize':'13px'}}}%%
flowchart LR
    tenancy["your TenancySpi"] --> router["TenancyRouter"]
    sink["Sink + Reader<br/>(OpenSearchSink)"] --> pipeline["Pipeline"]
    router --> pipeline
    pipeline --> handler["AppHandler"]
    auth["BearerAuth"] --> handler
    handler --> server["Helidon WebServer"]

    classDef step fill:#e8f0fe,stroke:#1a73e8,stroke-width:1.3px,color:#0b1f33;
    class tenancy,router,sink,pipeline,handler,auth,server step;
```

## Minimal: a working proxy in ~20 lines

```java
var cluster = new ClusterId("primary");
var sink = new OpenSearchSink(Map.of(cluster, "http://localhost:9200"));
var tenancy = new ReferenceTenancy(cluster, new IndexName("shared"));

Pipeline pipeline = new Pipeline(new TenancyRouter(tenancy), sink, sink);
AppHandler handler = new AppHandler(pipeline, new BearerAuth(Map.of()));

WebServer server = WebServer.builder()
        .port(9200)
        .routing(handler::route)
        .build()
        .start();
```

With an empty token map, `BearerAuth` runs in dev mode: the `x-tenant`
header is trusted directly. Never ship that; the next section adds real
tokens.

## Fuller: the optional layers

```java
Pipeline pipeline = new Pipeline(
        new TenancyRouter(tenancy), sink, sink,
        cfg.cursorAffinityKey().map(HmacCursorCodec::new)
                .map(c -> (CursorCodec) c),          // scroll/PIT affinity
        asyncSink,                                    // Kafka-backed async writes
        passthrough);                                  // tenant-agnostic bypass

AppHandler handler = new AppHandler(
        pipeline, new BearerAuth(Map.of("secret-token", "acme")),
        cfg.maxBodyBytes(), cfg.requireTlsForMutation(), observability)
        .withDebugEndpoints(cfg.debugEndpoints())
        .withForwardPolicy(new ForwardPolicy(true, List.of()))
        .withAdminToken("directive-admin-secret")
        .withCapture(Capture.redacting(captureSink));
```

Every one of these is additive: leave any of them unset and the proxy
behaves exactly as the minimal example. This is the same builder-layering
discipline the Rust `osproxy` uses — advanced capability is off until you
turn it on.

## Serving with TLS / mTLS

```java
var tls = Tls.builder()
        .privateKey(Keys.builder().keystore(k -> k.keystore(Resource.create(certPath))).build())
        .clientAuth(clientCaPath.isPresent() ? TlsClientAuth.REQUIRED : TlsClientAuth.NONE)
        .build();

WebServer server = WebServer.builder()
        .port(9200)
        .tls(tls)
        .routing(handler::route)
        .build()
        .start();
```

Pair this with `osproxy.require-tls-for-mutation=true` so a body-mutating
request over a plaintext listener is refused rather than silently accepted.

## You usually don't write `main` at all

`osproxy-server`'s `Main.start(ProxyConfig)` already assembles every layer
above from a `ProxyConfig`, wiring capture, async fan-out, FIPS, OTLP,
directive/placement polling, and the passthrough policy from config keys.
Run the binary directly, or read `Main.java` as a worked example, then
adapt it — it is deliberately one linear method, not a framework you extend.

→ [Configuration](/osproxy-java/07-configuration/)
