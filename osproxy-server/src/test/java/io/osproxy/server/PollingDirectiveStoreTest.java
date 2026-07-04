package io.osproxy.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.helidon.webserver.WebServer;
import io.osproxy.core.ManualClock;
import io.osproxy.observe.DiagLevel;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Fleet directives over plain HTTP pull: converge, keep-last-good, stop. */
class PollingDirectiveStoreTest {

    @Test
    void convergesOnTheSourceAndKeepsLastGoodThroughFailures() throws Exception {
        AtomicReference<String> served = new AtomicReference<>(
                "{\"baseline\":\"off\",\"directives\":[]}");
        AtomicReference<Integer> status = new AtomicReference<>(200);
        WebServer source = WebServer.builder()
                .port(0)
                .routing(r -> r.get("/directives", (req, res) ->
                        res.status(io.helidon.http.Status.create(status.get()))
                                .send(served.get())))
                .build()
                .start();
        try (var store = new PollingDirectiveStore(
                "http://localhost:" + source.port() + "/directives",
                DiagLevel.SHAPE, new ManualClock(), 3_600_000)) {

            // Until the first successful poll, the configured baseline holds.
            assertThat(store.load().baseline()).isEqualTo(DiagLevel.SHAPE);

            store.pollOnce();
            assertThat(store.load().baseline()).isEqualTo(DiagLevel.OFF);

            // The source publishes a directive: the next poll picks it up.
            served.set("""
                    {"baseline":"shape","directives":[
                      {"id":"fleet-debug","level":"verbose","ttl_seconds":60}]}
                    """);
            store.pollOnce();
            assertThat(store.load().directives()).hasSize(1);
            assertThat(store.load().directives().get(0).id()).isEqualTo("fleet-debug");

            // A malformed publish is refused; the last good set stays active.
            served.set("{\"directives\":[{\"tennant\":\"oops\"}]}");
            store.pollOnce();
            assertThat(store.load().directives()).hasSize(1);

            // A source outage (500) also keeps the last good set.
            status.set(500);
            store.pollOnce();
            assertThat(store.load().directives()).hasSize(1);

            // The source recovering replaces the set again.
            status.set(200);
            served.set("{\"baseline\":\"off\",\"directives\":[]}");
            store.pollOnce();
            assertThat(store.load().directives()).isEmpty();
            assertThat(store.load().baseline()).isEqualTo(DiagLevel.OFF);
        } finally {
            source.stop();
        }
    }

    @Test
    void anUnreachableSourceLeavesTheBaselineActive() {
        try (var store = new PollingDirectiveStore(
                "http://localhost:1/directives",
                DiagLevel.VERBOSE, new ManualClock(), 3_600_000)) {
            store.pollOnce();
            assertThat(store.load().baseline()).isEqualTo(DiagLevel.VERBOSE);
            assertThat(store.load().directives()).isEmpty();
        }
    }
}
