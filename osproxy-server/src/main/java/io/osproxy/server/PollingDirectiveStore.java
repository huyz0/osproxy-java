package io.osproxy.server;

import io.osproxy.core.Clock;
import io.osproxy.observe.DiagLevel;
import io.osproxy.observe.DirectiveSet;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The distributed directive store: polls a JSON document (the same wire
 * format the admin endpoint accepts) from any HTTP source — an etcd HTTP
 * gateway, a config service, an object store — and publishes it locally, so
 * every instance polling the same URL converges on the same directives
 * without a restart (the Rust project's etcd watch, over plain HTTP pull).
 *
 * <p>Keep-last-good: a fetch failure or a fail-closed decode refusal leaves
 * the previous set active — a flaky source degrades freshness, never
 * correctness. {@code load()} is a volatile read; polling runs on one
 * virtual thread.
 */
public final class PollingDirectiveStore implements DirectiveSet.Store, AutoCloseable {

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final URI source;
    private final DirectivesApi api;
    private final AtomicReference<DirectiveSet> current;
    private final Thread poller;
    private volatile boolean running = true;

    public PollingDirectiveStore(
            String sourceUrl, DiagLevel baseline, Clock clock, long pollMillis) {
        this.source = URI.create(sourceUrl);
        this.api = new DirectivesApi(clock);
        this.current = new AtomicReference<>(DirectiveSet.baseline(baseline));
        this.poller = Thread.ofVirtual()
                .name("osproxy-directive-poller")
                .start(() -> pollLoop(pollMillis));
    }

    @Override
    public DirectiveSet load() {
        return current.get();
    }

    private void pollLoop(long pollMillis) {
        while (running) {
            pollOnce();
            try {
                Thread.sleep(pollMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** One fetch+decode+publish attempt (package-visible for tests). */
    void pollOnce() {
        try {
            HttpResponse<byte[]> response = client.send(
                    HttpRequest.newBuilder(source)
                            .timeout(Duration.ofSeconds(5))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 200) {
                current.set(api.decode(response.body()));
            }
            // Non-200 or a decode refusal: keep the last good set.
        } catch (Exception e) {
            // Fetch failed: keep the last good set.
        }
    }

    @Override
    public void close() {
        running = false;
        poller.interrupt();
    }
}
