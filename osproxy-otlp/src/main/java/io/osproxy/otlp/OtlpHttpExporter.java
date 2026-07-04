package io.osproxy.otlp;

import io.osproxy.observe.ExplainDoc;
import io.osproxy.observe.SpanExporter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Semaphore;

/**
 * Fire-and-forget OTLP/HTTP export: POSTs each span to
 * {@code {endpoint}/v1/traces} asynchronously with a hard timeout. A bounded
 * in-flight budget sheds spans when the collector is slow — telemetry drops,
 * requests never do.
 */
public final class OtlpHttpExporter implements SpanExporter {

    private static final int MAX_IN_FLIGHT = 64;
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient client;
    private final URI tracesUri;
    private final String serviceName;
    private final Semaphore inFlight = new Semaphore(MAX_IN_FLIGHT);

    public OtlpHttpExporter(String endpoint, String serviceName) {
        this.client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        this.tracesUri = URI.create(
                endpoint.endsWith("/") ? endpoint + "v1/traces" : endpoint + "/v1/traces");
        this.serviceName = serviceName;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public void export(ExplainDoc doc, String spanId, long endUnixNanos) {
        // Saturated: drop this span rather than queue behind a slow collector.
        if (!inFlight.tryAcquire()) {
            return;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(tracesUri)
                    .timeout(TIMEOUT)
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(
                            OtlpEncoder.encode(serviceName, doc, spanId, endUnixNanos)))
                    .build();
            client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .whenComplete((response, error) -> inFlight.release());
        } catch (RuntimeException e) {
            inFlight.release(); // encode/build failed: telemetry never throws
        }
    }
}
