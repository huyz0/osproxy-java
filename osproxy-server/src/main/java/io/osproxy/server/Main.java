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
        Pipeline pipeline = new Pipeline(
                new TenancyRouter(new ReferenceTenancy(cluster, new IndexName(cfg.index()))),
                sink, sink,
                cfg.cursorAffinityKey().map(HmacCursorCodec::new).map(c -> (io.osproxy.engine.CursorCodec) c));
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
        AppHandler handler = new AppHandler(
                pipeline, new BearerAuth(cfg.tokens()),
                cfg.maxBodyBytes(), cfg.requireTlsForMutation(), observability);
        cfg.directiveAdminToken().ifPresent(handler::withAdminToken);
        var builder = WebServer.builder()
                .port(cfg.port())
                .routing(handler::route);
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
