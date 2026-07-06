package io.osproxy.server;

import io.helidon.common.configurable.Resource;
import io.helidon.common.pki.Keys;
import io.helidon.common.tls.Tls;
import io.helidon.common.tls.TlsClientAuth;
import io.helidon.config.Config;
import io.helidon.webserver.WebServer;
import io.osproxy.config.ProxyConfig;
import io.osproxy.core.ClusterId;
import io.osproxy.core.IndexName;
import io.osproxy.engine.Pipeline;
import io.osproxy.sink.OpenSearchSink;
import io.osproxy.tenancy.TenancyRouter;
import java.util.Map;

/** The reference server: config → wiring → serve. */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        ProxyConfig cfg = ProxyConfig.load(Config.create());
        WebServer server = start(cfg);
        System.out.println("osproxy-java listening on port " + server.port()
                + (new BearerAuth(cfg.tokens()).devMode()
                        ? " (DEV MODE: x-tenant header trusted)" : ""));
    }

    /** Wires the reference stack and starts the server (tests call this too). */
    public static WebServer start(ProxyConfig cfg) {
        if (cfg.fips()) {
            CryptoPosture.engageFips();
        }
        ClusterId cluster = new ClusterId("primary");
        OpenSearchSink sink = new OpenSearchSink(Map.of(cluster, cfg.upstream()));
        // Async write mode goes live only with a broker configured; the sink
        // adapter blocks on the acked produce (that is the 202 contract).
        var asyncSink = cfg.fanoutBootstrapServers()
                .map(servers -> {
                    var producer = new io.osproxy.kafka.KafkaAckProducer(servers);
                    return (io.osproxy.engine.AsyncWrites.AsyncWriteSink) (key, envelope) ->
                            producer.produceAcked(
                                    cfg.fanoutTopic(),
                                    key.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                    envelope);
                });
        var reference = new ReferenceTenancy(cluster, new IndexName(cfg.index()));
        io.osproxy.spi.TenancySpi tenancy = cfg.placementsUrl()
                .<io.osproxy.spi.TenancySpi>map(url -> {
                    var table = new io.osproxy.tenancy.PlacementTable();
                    table.setDefault(new io.osproxy.spi.Placement.SharedIndex(
                            cluster, new IndexName(cfg.index()),
                            java.util.List.of(new io.osproxy.spi.InjectedField(
                                    ReferenceTenancy.TENANT_FIELD,
                                    io.osproxy.spi.InjectedValue.PartitionIdValue.INSTANCE))));
                    new PollingPlacementStore(url, table, cfg.placementsPollSeconds() * 1000L);
                    return new io.osproxy.tenancy.MigrationGatedTenancy(
                            reference, table, new io.osproxy.tenancy.MigrationControl(table));
                })
                .orElse(reference);
        // Tenant-agnostic passthrough: set only when both the cluster and its
        // endpoint are configured (fail-closed default is full tenancy).
        var passthrough = cfg.passthroughCluster().map(clusterName -> new io.osproxy.engine.PassthroughPolicy(
                new ClusterId(clusterName), cfg.passthroughEndpoint(), cfg.passthroughIndices()));
        Pipeline pipeline = new Pipeline(
                new TenancyRouter(tenancy),
                sink, sink,
                cfg.cursorAffinityKey().map(HmacCursorCodec::new).map(c -> (io.osproxy.engine.CursorCodec) c),
                asyncSink, passthrough)
                .withDeleteByQueryExpansion(cfg.deleteByQueryExpansion());
        // Admin pass-through: refused by default; opt in with an allow-list.
        cfg.adminCluster().ifPresent(clusterName -> {
            var policy = new io.osproxy.engine.AdminPolicy(
                    new ClusterId(clusterName), cfg.adminAllowedPrefixes(), cfg.adminEndpoint());
            pipeline.withAdminPolicy(policy);
        });
        var requestLog = cfg.logRequests()
                ? java.util.Optional.of(System.out) : java.util.Optional.<java.io.PrintStream>empty();
        var baseline = cfg.logRequests()
                ? io.osproxy.observe.DiagLevel.VERBOSE : io.osproxy.observe.DiagLevel.SHAPE;
        Observability observability = cfg.directivesUrl()
                .map(url -> new Observability(
                        512, requestLog,
                        new PollingDirectiveStore(
                                url, baseline, new io.osproxy.core.SystemClock(),
                                cfg.directivesPollSeconds() * 1000L),
                        new io.osproxy.core.SystemClock()))
                .orElseGet(() -> new Observability(512, requestLog));
        cfg.otlpEndpoint().ifPresent(endpoint -> observability.withExporter(
                new io.osproxy.otlp.OtlpHttpExporter(endpoint, cfg.serviceName())));
        if (cfg.logDiagnosticCaptures()) {
            observability.withDiagnosticSink(new StdoutDiagnosticSink());
        }
        if (cfg.tenantMetricsEnabled()) {
            var tenantMetrics = new io.osproxy.observe.TenantMetrics();
            observability.withTenantMetrics(tenantMetrics);
            // OTLP export of tenant counters piggybacks on the same
            // otlp-endpoint config as span export; a deployment that wants
            // per-tenant counters but not OTLP still gets them at
            // /_osproxy/metrics/tenants, this is purely additive.
            cfg.otlpEndpoint().ifPresent(endpoint -> new TenantMetricsExportScheduler(
                    tenantMetrics,
                    new io.osproxy.otlp.OtlpHttpMetricsExporter(endpoint, cfg.serviceName()),
                    cfg.tenantMetricsExportIntervalSeconds() * 1000L));
        }
        BearerAuth auth = new BearerAuth(cfg.tokens());
        AppHandler handler = new AppHandler(
                pipeline, auth,
                cfg.maxBodyBytes(), cfg.requireTlsForMutation(), observability)
                .withDebugEndpoints(cfg.debugEndpoints())
                .withForwardPolicy(new ForwardPolicy(
                        cfg.headerForwardingEnabled(), cfg.headerForwardingDeny()));
        cfg.directiveAdminToken().ifPresent(handler::withAdminToken);
        var builder = WebServer.builder()
                .port(cfg.port())
                .routing(handler::route)
                // gRPC ingress: same port/TLS as REST, HTTP/2's own protocol
                // handler distinguishes a gRPC call by its content-type.
                .addRouting(io.helidon.webserver.grpc.GrpcRouting.builder()
                        .intercept(new GrpcMetadataInterceptor())
                        .service(new GrpcDocumentService(handler, auth).descriptor()));
        cfg.tls().ifPresent(tls -> builder.tls(tls(tls)));
        return builder.build().start();
    }

    /** Builds the TLS listener from PEM paths; a client CA enables mTLS. */
    static Tls tls(io.osproxy.config.ProxyConfig.TlsSettings settings) {
        Keys keys = Keys.builder()
                .pem(pem -> pem
                        .key(Resource.create(java.nio.file.Path.of(settings.keyPath())))
                        .certChain(Resource.create(java.nio.file.Path.of(settings.certPath()))))
                .build();
        // Always pinned to the FIPS-approved set (harmless off-FIPS,
        // mandatory on): TLS 1.2/1.3, AES-GCM only.
        var tls = Tls.builder()
                .privateKey(keys.privateKey().orElseThrow())
                .privateKeyCertChain(keys.certChain())
                .enabledProtocols(CryptoPosture.APPROVED_PROTOCOLS)
                .enabledCipherSuites(CryptoPosture.APPROVED_SUITES);
        settings.clientCaPath().ifPresent(ca -> tls
                .trust(Keys.builder()
                        .pem(pem -> pem.certificates(
                                Resource.create(java.nio.file.Path.of(ca))))
                        .build()
                        .certs())
                .clientAuth(TlsClientAuth.REQUIRED));
        return tls.build();
    }
}
