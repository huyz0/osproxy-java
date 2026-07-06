package io.osproxy.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.osproxy.bench.FootprintProfile;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * The footprint/soak e2e (the Rust project's soak.rs analog): spawns the
 * real {@code osproxy-server} artifact as its own OS process — not
 * in-process — so {@code /proc/<pid>/statm} reports the proxy's actual
 * resident set, not the test JVM's. Measures idle RSS, drives a sustained
 * request soak, measures again, and judges growth via the either/or bound
 * (a tiny idle footprint makes a small absolute growth look huge as a
 * ratio). Tagged {@code integration} (Linux-only: reads {@code /proc}).
 */
@Tag("integration")
class SoakE2eTest {

    private static final int SOAK_REQUESTS = 4_000;
    private static final long PAGE_SIZE_BYTES = 4096; // Linux default; statm is in pages

    private static GenericContainer<?> opensearch;
    private static Process server;
    private static int port;
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @BeforeAll
    static void start() throws Exception {
        opensearch = new GenericContainer<>("opensearchproject/opensearch:2.11.0")
                .withEnv("discovery.type", "single-node")
                .withEnv("plugins.security.disabled", "true")
                .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
                .withExposedPorts(9200)
                .waitingFor(Wait.forHttp("/").forPort(9200))
                .withStartupTimeout(Duration.ofMinutes(3));
        opensearch.start();
        String upstream = "http://" + opensearch.getHost() + ":" + opensearch.getMappedPort(9200);

        port = findFreePort();
        String javaHome = System.getProperty("java.home");
        ProcessBuilder builder = new ProcessBuilder(
                javaHome + "/bin/java",
                // Bounded heap: without it the JVM grows RSS toward a
                // committed-but-mostly-idle steady state after any burst,
                // which is heap policy, not a leak, and would swamp the
                // growth signal we're trying to measure.
                "-Xms64m", "-Xmx256m",
                "-cp", System.getProperty("java.class.path"),
                "io.osproxy.server.Main")
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.environment().put("OSPROXY_PORT", String.valueOf(port));
        builder.environment().put("OSPROXY_UPSTREAM", upstream);
        builder.environment().put("OSPROXY_INDEX", "shared");
        server = builder.start();
        awaitReady();
    }

    private static int findFreePort() throws IOException {
        try (var socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void awaitReady() throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                var response = CLIENT.send(
                        HttpRequest.newBuilder(URI.create(
                                        "http://localhost:" + port + AppHandler.METRICS_PATH))
                                .GET().build(),
                        HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() == 200) {
                    return;
                }
            } catch (Exception ignored) {
                // not up yet
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException("osproxy-server did not become ready in time");
    }

    @AfterAll
    static void stop() {
        if (server != null) {
            server.destroy();
        }
        if (opensearch != null) {
            opensearch.stop();
        }
    }

    /**
     * Forces a full GC in the target process (via the bundled {@code jcmd}),
     * then reads RSS straight from its {@code /proc} entry. Without forcing
     * a collection first, RSS reflects whatever garbage has not been
     * reclaimed yet rather than the process's actual live-set footprint,
     * which is what a leak guard needs to compare.
     */
    private static long residentSetBytes() throws IOException, InterruptedException {
        String javaHome = System.getProperty("java.home");
        new ProcessBuilder(javaHome + "/bin/jcmd", String.valueOf(server.pid()), "GC.run")
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
                .waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
        Thread.sleep(500); // let the collection and RSS actually settle
        List<String> fields = List.of(
                Files.readString(Path.of("/proc/" + server.pid() + "/statm"),
                        StandardCharsets.UTF_8).strip().split("\\s+"));
        long residentPages = Long.parseLong(fields.get(1));
        return residentPages * PAGE_SIZE_BYTES;
    }

    @Test
    void footprintStaysBoundedUnderSustainedTraffic() throws Exception {
        // A brief settle so idle RSS reflects a warmed-up steady state, not
        // the moment JIT/class-loading finished.
        Thread.sleep(1_000);
        long idle = residentSetBytes();

        int failures = 0;
        for (int i = 0; i < SOAK_REQUESTS; i++) {
            var request = HttpRequest.newBuilder(
                            URI.create("http://localhost:" + port + "/orders/_doc/" + (i % 500)))
                    .PUT(HttpRequest.BodyPublishers.ofString("{\"msg\":\"soak\",\"n\":" + i + "}"))
                    .header("x-tenant", "acme")
                    .build();
            int status = CLIENT.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status >= 300) {
                failures++;
            }
        }
        assertThat(failures).isZero();

        long soak = residentSetBytes();
        var profile = new FootprintProfile(idle, soak);
        System.out.println("\n=== osproxy-java footprint profile ===\n" + profile.render());

        // 2.5x growth OR 150 MiB absolute: G1 retains reclaimable-but-
        // uncollected regions after one GC.run as a matter of policy, not a
        // leak, so the bound is deliberately wide of any single measured
        // run to avoid flaking on ordinary GC-timing noise (RSS stays flat
        // across repeated runs against a real leak-free build).
        assertThat(profile.judge(2.5, 150 * 1024 * 1024L))
                .as("footprint growth: %s", profile.render())
                .isTrue();
    }
}
