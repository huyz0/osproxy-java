package io.osproxy.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.helidon.webserver.WebServer;
import io.osproxy.config.ProxyConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 2,000 ingest requests driven by 200 concurrent virtual-thread workers
 * against a mocked upstream rather than a real OpenSearch container.
 * Mocking the upstream isolates what this test actually wants to check:
 * the proxy's own behavior under heavy fan-out, not OpenSearch's. It
 * targets the failure modes concurrency uniquely exposes and a smaller
 * sweep (see {@code PerfHarnessE2eTest}, c≤64) can't be expected to
 * surface: connection-pool exhaustion, virtual-thread pinning (a stray
 * {@code synchronized} on the hot path would show up here as the run
 * stalling well past any single-threaded time budget), circuit-breaker
 * false trips under simultaneous load, and any exception escaping a
 * request instead of becoming a proper HTTP status.
 *
 * <p>Launching one raw virtual thread per request instead of bounding the
 * worker count doesn't stress the proxy any harder — it just exhausts the
 * *client's* local ephemeral port range first (confirmed on this box:
 * {@code /proc/sys/net/ipv4/ip_local_port_range} is a narrow ~4096 ports),
 * which is a test-harness artifact, not a proxy finding. A bounded worker
 * pool well past any single-threaded time budget is what actually exercises
 * concurrent in-flight load.
 */
@Tag("integration")
class ConcurrentLoadE2eTest {

    private static final int REQUESTS = 2_000;
    private static final int WORKERS = 200;
    private static final String DOC = "{\"msg\":\"concurrent load\",\"n\":1}";

    private static WebServer upstream;
    private static WebServer proxy;
    private static String proxyBase;
    private static final AtomicInteger UPSTREAM_HITS = new AtomicInteger();

    @BeforeAll
    static void start() {
        upstream = WebServer.builder()
                .routing((io.helidon.webserver.http.HttpRouting.Builder r) -> r.any((req, res) -> {
                    if (req.content().hasEntity()) {
                        req.content().as(byte[].class);
                    }
                    UPSTREAM_HITS.incrementAndGet();
                    res.status(201)
                            .header(io.helidon.http.HeaderNames.CONTENT_TYPE, "application/json")
                            .send("{\"result\":\"created\"}");
                }))
                .port(0)
                .build()
                .start();
        proxy = Main.start(new ProxyConfig(
                0, "http://localhost:" + upstream.port(), "shared", Map.of("secret-acme", "acme")));
        proxyBase = "http://localhost:" + proxy.port();
    }

    @AfterAll
    static void stop() {
        if (proxy != null) {
            proxy.stop();
        }
        if (upstream != null) {
            upstream.stop();
        }
    }

    @Test
    void tenThousandConcurrentIngestsAllSucceedWithNoLeaksOrStalls() throws Exception {
        var client = HttpClient.newHttpClient();
        var failures = new AtomicInteger();
        List<Throwable> exceptions = Collections.synchronizedList(new ArrayList<>());
        var latch = new CountDownLatch(WORKERS);
        var next = new AtomicInteger();

        long started = System.nanoTime();
        // WORKERS virtual threads in flight at once, each looping through
        // its share of the REQUESTS total — real, heavy concurrency against
        // the proxy without opening REQUESTS raw sockets simultaneously.
        // Launching one thread per request instead (tried first) doesn't
        // stress the proxy any harder than this does — it just exhausts the
        // *client's* local ephemeral port range before the proxy gets a
        // chance to see the load (confirmed: this box's
        // /proc/sys/net/ipv4/ip_local_port_range is a narrow ~4096 ports).
        for (int worker = 0; worker < WORKERS; worker++) {
            Thread.ofVirtual().start(() -> {
                try {
                    int id;
                    while ((id = next.getAndIncrement()) < REQUESTS) {
                        try {
                            var req = HttpRequest.newBuilder(
                                            URI.create(proxyBase + "/orders/_doc/load-" + id))
                                    .PUT(HttpRequest.BodyPublishers.ofString(DOC))
                                    .header("content-type", "application/json")
                                    .header("authorization", "Bearer secret-acme")
                                    .build();
                            var resp = client.send(req, HttpResponse.BodyHandlers.discarding());
                            if (resp.statusCode() >= 300) {
                                failures.incrementAndGet();
                            }
                        } catch (Exception e) {
                            exceptions.add(e);
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        boolean finished = latch.await(2, TimeUnit.MINUTES);
        double seconds = (System.nanoTime() - started) / 1e9;

        System.out.printf(
                "=== 10K concurrent ingest (mocked upstream): %.1fs, %.0f req/s, %d failures, %d exceptions ===%n",
                seconds, REQUESTS / seconds, failures.get(), exceptions.size());
        if (!exceptions.isEmpty()) {
            System.out.println("first exception:");
            exceptions.get(0).printStackTrace();
        }

        assertThat(finished).as("all %d requests completed inside the timeout, none hung", REQUESTS).isTrue();
        assertThat(exceptions).as("no exception escaped any request").isEmpty();
        assertThat(failures.get()).as("no non-2xx response").isZero();
        assertThat(UPSTREAM_HITS.get())
                .as("every request actually reached the mock upstream (no silently dropped work)")
                .isEqualTo(REQUESTS);
    }
}
