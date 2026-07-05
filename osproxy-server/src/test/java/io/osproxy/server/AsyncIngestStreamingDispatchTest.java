package io.osproxy.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.helidon.webserver.WebServer;
import io.osproxy.core.ClusterId;
import io.osproxy.core.IndexName;
import io.osproxy.engine.Pipeline;
import io.osproxy.sink.OpenSearchSink;
import io.osproxy.tenancy.TenancyRouter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Regression test for a real bug found by {@code KafkaFanoutE2eTest}
 * failing against a live broker: {@code
 * AppHandler}'s dispatch chose the streaming ingest path
 * ({@link Pipeline#ingestDocStreaming}) purely from {@code
 * Pipeline#supportsStreamingIngest()} — a tenancy-configuration property —
 * with no check for the request's async-write-mode header at all.
 * {@code ingestDocStreaming} has no async handling of its own; it always
 * writes straight to the sink. An async-mode request routed there silently
 * skipped the durable-enqueue contract and dialed the real (in this test,
 * unreachable) upstream instead, surfacing as a 502 where a 202 was
 * expected. This test reproduces that without needing Docker/Kafka: the
 * upstream is an unreachable port, so if the bug regresses, the request
 * fails with 502 instead of the async path's 202.
 */
class AsyncIngestStreamingDispatchTest {

    @Test
    void anAsyncModeSingleDocIngestSkipsTheStreamingPathEvenWhenTenancySupportsIt() throws Exception {
        var cluster = new ClusterId("primary");
        var reference = new ReferenceTenancy(cluster, new IndexName("shared"));
        // Deliberately unreachable: a bug that routes this request through
        // the streaming (non-async) path would try to dial this and fail
        // with a connection error (502), not answer 202.
        var sink = new OpenSearchSink(Map.of(cluster, "http://localhost:59201"));

        var enqueued = new AtomicReference<byte[]>();
        var asyncSink = (io.osproxy.engine.AsyncWrites.AsyncWriteSink) (key, envelope) ->
                enqueued.set(envelope);
        var pipeline = new Pipeline(
                new TenancyRouter(reference), sink, sink,
                Optional.empty(), Optional.of(asyncSink));
        assertThat(pipeline.supportsStreamingIngest())
                .as("precondition: the reference tenancy must qualify for streaming ingest, "
                        + "or this test isn't exercising the bug it's meant to catch")
                .isTrue();

        WebServer proxy = WebServer.builder()
                .port(0)
                .routing(new AppHandler(pipeline, new BearerAuth(Map.of()))::route)
                .build()
                .start();
        try {
            var client = HttpClient.newHttpClient();
            var req = HttpRequest.newBuilder(
                            URI.create("http://localhost:" + proxy.port() + "/orders/_doc/1"))
                    .PUT(HttpRequest.BodyPublishers.ofString("{\"msg\":\"queued\"}"))
                    .header("x-tenant", "acme")
                    .header("x-osproxy-write-mode", "async")
                    .build();
            var resp = client.send(req, HttpResponse.BodyHandlers.ofString());

            assertThat(resp.statusCode()).isEqualTo(202);
            assertThat(resp.body()).contains("\"status\":\"accepted\"");
            assertThat(enqueued.get())
                    .as("the write must have gone through the async sink, not the upstream")
                    .isNotNull();
        } finally {
            proxy.stop();
        }
    }
}
