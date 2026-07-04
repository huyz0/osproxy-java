package io.osproxy.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.helidon.webserver.WebServer;
import io.helidon.webserver.http.HttpRouting;
import io.osproxy.core.ClusterId;
import io.osproxy.core.IndexName;
import io.osproxy.engine.PassthroughPolicy;
import io.osproxy.engine.Pipeline;
import io.osproxy.sink.MemorySink;
import io.osproxy.tenancy.TenancyRouter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Tenant-agnostic passthrough end to end: a matching logical index skips
 * tenancy and forwards verbatim (method/path/body/headers) to the configured
 * cluster; a non-matching index stays fully tenanted (fail-closed).
 */
class PassthroughE2eTest {

    @Test
    void aMatchingIndexForwardsVerbatimIncludingClientHeaders() throws Exception {
        var seenMethod = new AtomicReference<String>();
        var seenPath = new AtomicReference<String>();
        var seenBody = new AtomicReference<String>();
        var seenAuth = new AtomicReference<String>();
        WebServer upstream = WebServer.builder()
                .routing((HttpRouting.Builder r) -> r.any((req, res) -> {
                    seenMethod.set(req.prologue().method().text());
                    seenPath.set(req.path().rawPath());
                    seenAuth.set(req.headers()
                            .first(io.helidon.http.HeaderNames.AUTHORIZATION).orElse(null));
                    seenBody.set(req.content().hasEntity()
                            ? new String(req.content().as(byte[].class)) : "");
                    res.status(200).send("{\"ok\":true}");
                }))
                .port(0)
                .build()
                .start();
        try {
            var cluster = new ClusterId("legacy");
            var policy = PassthroughPolicy.of(cluster, "http://localhost:" + upstream.port())
                    .withIndexPrefixes(List.of("legacy-"));
            var sink = new io.osproxy.sink.OpenSearchSink(
                    Map.of(cluster, "http://localhost:" + upstream.port()));
            var reference = new ReferenceTenancy(cluster, new IndexName("shared"));
            Pipeline pipeline = new Pipeline(
                    new TenancyRouter(reference), sink, sink,
                    Optional.empty(), Optional.empty(), Optional.of(policy));
            WebServer proxy = WebServer.builder()
                    .port(0)
                    .routing(new AppHandler(pipeline, new BearerAuth(Map.of()))::route)
                    .build()
                    .start();
            try {
                var client = HttpClient.newHttpClient();
                var req = HttpRequest.newBuilder(
                                URI.create("http://localhost:" + proxy.port()
                                        + "/legacy-orders/_doc/some-id"))
                        .PUT(HttpRequest.BodyPublishers.ofString("{\"raw\":true}"))
                        .header("x-tenant", "acme")
                        .header("authorization", "Bearer client-cred")
                        .build();
                var resp = client.send(req, HttpResponse.BodyHandlers.ofString());

                assertThat(resp.statusCode()).isEqualTo(200);
                assertThat(resp.body()).isEqualTo("{\"ok\":true}");
                assertThat(seenMethod.get()).isEqualTo("PUT");
                assertThat(seenPath.get()).isEqualTo("/legacy-orders/_doc/some-id");
                assertThat(seenBody.get()).isEqualTo("{\"raw\":true}");
                // Pass-all header forwarding (the default) carries the client's
                // own Authorization through — passthrough trusts the sidecar.
                assertThat(seenAuth.get()).isEqualTo("Bearer client-cred");
            } finally {
                proxy.stop();
            }
        } finally {
            upstream.stop();
        }
    }

    @Test
    void aBodyLargerThanMaxBodyBytesStreamsThroughWithoutBeingCapped() throws Exception {
        var seenLength = new AtomicReference<Long>();
        WebServer upstream = WebServer.builder()
                .routing((HttpRouting.Builder r) -> r.any((req, res) -> {
                    long total = 0;
                    if (req.content().hasEntity()) {
                        try (var in = req.content().inputStream()) {
                            byte[] chunk = new byte[8192];
                            int n;
                            while ((n = in.read(chunk)) >= 0) {
                                total += n;
                            }
                        }
                    }
                    seenLength.set(total);
                    res.status(200).send("{\"ok\":true}");
                }))
                .port(0)
                .build()
                .start();
        try {
            var cluster = new ClusterId("legacy");
            var policy = PassthroughPolicy.of(cluster, "http://localhost:" + upstream.port())
                    .withIndexPrefixes(List.of("legacy-"));
            var sink = new io.osproxy.sink.OpenSearchSink(
                    Map.of(cluster, "http://localhost:" + upstream.port()));
            var reference = new ReferenceTenancy(cluster, new IndexName("shared"));
            Pipeline pipeline = new Pipeline(
                    new TenancyRouter(reference), sink, sink,
                    Optional.empty(), Optional.empty(), Optional.of(policy));
            // A tiny cap: a body this much larger than it would 413 on the
            // buffered (non-passthrough) path — proving the streaming path
            // genuinely bypasses maxBodyBytes rather than just raising it.
            long tinyCap = 1024;
            WebServer proxy = WebServer.builder()
                    .port(0)
                    .routing(new AppHandler(pipeline, new BearerAuth(Map.of()), tinyCap, false)
                            ::route)
                    .build()
                    .start();
            try {
                var client = HttpClient.newHttpClient();
                byte[] bigBody = new byte[16 * 1024 * 1024]; // 16 MiB >> the 1 KiB cap
                java.util.Arrays.fill(bigBody, (byte) 'x');
                var req = HttpRequest.newBuilder(
                                URI.create("http://localhost:" + proxy.port()
                                        + "/legacy-orders/_bulk"))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(bigBody))
                        .header("x-tenant", "acme")
                        .build();
                var resp = client.send(req, HttpResponse.BodyHandlers.ofString());

                assertThat(resp.statusCode()).isEqualTo(200);
                assertThat(seenLength.get()).isEqualTo((long) bigBody.length);
            } finally {
                proxy.stop();
            }
        } finally {
            upstream.stop();
        }
    }

    @Test
    void theDenyListStillAppliesOnAPassthroughForwardedRequest() throws Exception {
        var seenAuth = new AtomicReference<String>();
        var seenTenant = new AtomicReference<String>();
        WebServer upstream = WebServer.builder()
                .routing((HttpRouting.Builder r) -> r.any((req, res) -> {
                    seenAuth.set(req.headers()
                            .first(io.helidon.http.HeaderNames.AUTHORIZATION).orElse(null));
                    seenTenant.set(req.headers()
                            .first(io.helidon.http.HeaderNames.create("x-tenant")).orElse(null));
                    res.status(200).send("{\"ok\":true}");
                }))
                .port(0)
                .build()
                .start();
        try {
            var cluster = new ClusterId("legacy");
            var policy = PassthroughPolicy.of(cluster, "http://localhost:" + upstream.port())
                    .withIndexPrefixes(List.of("legacy-"));
            var sink = new io.osproxy.sink.OpenSearchSink(
                    Map.of(cluster, "http://localhost:" + upstream.port()));
            var reference = new ReferenceTenancy(cluster, new IndexName("shared"));
            Pipeline pipeline = new Pipeline(
                    new TenancyRouter(reference), sink, sink,
                    Optional.empty(), Optional.empty(), Optional.of(policy));
            // A deny-listed header must be dropped on the passthrough path
            // exactly like every other endpoint's own sink call — both flow
            // through the one ForwardHeaders binding computed per request.
            WebServer proxy = WebServer.builder()
                    .port(0)
                    .routing(new AppHandler(pipeline, new BearerAuth(Map.of()))
                            .withForwardPolicy(new ForwardPolicy(true, List.of("authorization")))
                            ::route)
                    .build()
                    .start();
            try {
                var client = HttpClient.newHttpClient();
                var req = HttpRequest.newBuilder(
                                URI.create("http://localhost:" + proxy.port()
                                        + "/legacy-orders/_doc/some-id"))
                        .PUT(HttpRequest.BodyPublishers.ofString("{}"))
                        .header("x-tenant", "acme")
                        .header("authorization", "Bearer client-cred")
                        .build();
                var resp = client.send(req, HttpResponse.BodyHandlers.ofString());

                assertThat(resp.statusCode()).isEqualTo(200);
                assertThat(seenAuth.get()).as("denied header must not reach the upstream").isNull();
                assertThat(seenTenant.get()).as("non-denied headers still pass").isEqualTo("acme");
            } finally {
                proxy.stop();
            }
        } finally {
            upstream.stop();
        }
    }

    @Test
    void aNonMatchingIndexStaysFullyTenantedNotForwarded() throws Exception {
        var cluster = new ClusterId("legacy");
        var policy = PassthroughPolicy.of(cluster, "http://unused:9200")
                .withIndexPrefixes(List.of("legacy-"));
        MemorySink memSink = new MemorySink();
        var reference = new ReferenceTenancy(new ClusterId("primary"), new IndexName("shared"));
        Pipeline pipeline = new Pipeline(
                new TenancyRouter(reference), memSink, memSink,
                Optional.empty(), Optional.empty(), Optional.of(policy));
        WebServer proxy = WebServer.builder()
                .port(0)
                .routing(new AppHandler(pipeline, new BearerAuth(Map.of()))::route)
                .build()
                .start();
        try {
            var client = HttpClient.newHttpClient();
            var req = HttpRequest.newBuilder(
                            URI.create("http://localhost:" + proxy.port() + "/orders/_doc/1"))
                    .PUT(HttpRequest.BodyPublishers.ofString("{}"))
                    .header("x-tenant", "acme")
                    .build();
            var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            // Tenancy handled it (MemorySink, not the passthrough cluster).
            assertThat(resp.statusCode()).isEqualTo(201);
        } finally {
            proxy.stop();
        }
    }
}
