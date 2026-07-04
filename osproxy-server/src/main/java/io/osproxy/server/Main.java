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
        ClusterId cluster = new ClusterId("primary");
        OpenSearchSink sink = new OpenSearchSink(Map.of(cluster, cfg.upstream()));
        Pipeline pipeline = new Pipeline(
                new TenancyRouter(new ReferenceTenancy(cluster, new IndexName(cfg.index()))),
                sink, sink);
        AppHandler handler = new AppHandler(
                pipeline, new BearerAuth(cfg.tokens()),
                cfg.maxBodyBytes(), cfg.requireTlsForMutation());
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
        var tls = Tls.builder()
                .privateKey(keys.privateKey().orElseThrow())
                .privateKeyCertChain(keys.certChain());
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
