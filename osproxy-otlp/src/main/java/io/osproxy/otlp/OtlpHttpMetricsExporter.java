package io.osproxy.otlp;

import io.osproxy.observe.MetricsExporter;
import io.osproxy.observe.TenantMetrics;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Semaphore;

/**
 * Fire-and-forget OTLP/HTTP metrics export: POSTs each snapshot to {@code
 * {endpoint}/v1/metrics} asynchronously with a hard timeout. Mirrors {@link
 * OtlpHttpExporter}'s shed-under-load stance — a slow collector drops a
 * snapshot (the next poll sends fresher cumulative totals anyway), never
 * queues behind it.
 */
public final class OtlpHttpMetricsExporter implements MetricsExporter {

    private static final int MAX_IN_FLIGHT = 4;
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient client;
    private final URI metricsUri;
    private final String serviceName;
    private final Semaphore inFlight = new Semaphore(MAX_IN_FLIGHT);

    public OtlpHttpMetricsExporter(String endpoint, String serviceName) {
        this.client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        this.metricsUri = URI.create(
                endpoint.endsWith("/") ? endpoint + "v1/metrics" : endpoint + "/v1/metrics");
        this.serviceName = serviceName;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public void export(List<TenantMetrics.TenantSnapshot> snapshot, long unixNanos) {
        if (!inFlight.tryAcquire()) {
            return;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(metricsUri)
                    .timeout(TIMEOUT)
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(
                            OtlpMetricsEncoder.encode(serviceName, snapshot, unixNanos)))
                    .build();
            client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .whenComplete((response, error) -> inFlight.release());
        } catch (RuntimeException e) {
            inFlight.release(); // encode/build failed: telemetry never throws
        }
    }
}
