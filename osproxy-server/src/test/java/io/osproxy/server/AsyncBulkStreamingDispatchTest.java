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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The bulk analog of {@link AsyncIngestStreamingDispatchTest}: {@code
 * AppHandler}'s dispatch must not route an async-mode {@code _bulk} request
 * into {@link AppHandler#streamBulk}, since that path's streaming twin
 * ({@code MultiOps#bulkStreaming}) dispatches straight to the upstream sink
 * with no async handling of its own. The upstream here is unreachable, so a
 * regression surfaces as a 200-with-per-item-502s (or a hang) instead of the
 * async path's per-item 202/op_id.
 */
class AsyncBulkStreamingDispatchTest {

    @Test
    void anAsyncModeBulkSkipsTheStreamingPathAndEnqueuesEveryItem() throws Exception {
        var cluster = new ClusterId("primary");
        var reference = new ReferenceTenancy(cluster, new IndexName("shared"));
        // Deliberately unreachable, same trick as the single-doc regression
        // test: a bug that routes this through the streaming path would try
        // to dial this and fail, rather than answering via the async sink.
        var sink = new OpenSearchSink(Map.of(cluster, "http://localhost:59201"));

        List<byte[]> enqueued = new ArrayList<>();
        var asyncSink = (io.osproxy.engine.AsyncWrites.AsyncWriteSink) (key, envelope) ->
                enqueued.add(envelope);
        var pipeline = new Pipeline(
                new TenancyRouter(reference), sink, sink,
                Optional.empty(), Optional.of(asyncSink));

        WebServer proxy = WebServer.builder()
                .port(0)
                .routing(new AppHandler(pipeline, new BearerAuth(Map.of()))::route)
                .build()
                .start();
        try {
            var client = HttpClient.newHttpClient();
            String ndjson = """
                    {"index":{"_index":"orders","_id":"1"}}
                    {"msg":"a"}
                    {"index":{"_index":"orders","_id":"2"}}
                    {"msg":"b"}
                    """;
            var req = HttpRequest.newBuilder(URI.create("http://localhost:" + proxy.port() + "/_bulk"))
                    .POST(HttpRequest.BodyPublishers.ofString(ndjson))
                    .header("x-tenant", "acme")
                    .header("x-osproxy-write-mode", "async")
                    .build();
            var resp = client.send(req, HttpResponse.BodyHandlers.ofString());

            assertThat(resp.statusCode()).isEqualTo(200);
            assertThat(resp.body()).contains("\"status\":202").contains("\"result\":\"accepted\"");
            assertThat(enqueued)
                    .as("both items must have gone through the async sink, not the upstream")
                    .hasSize(2);
        } finally {
            proxy.stop();
        }
    }
}
