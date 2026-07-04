package io.osproxy.sink;

import io.helidon.webclient.api.HttpClientResponse;
import io.helidon.webclient.api.WebClient;
import io.osproxy.core.Clock;
import io.osproxy.core.ClusterId;
import io.osproxy.core.ErrorCode;
import io.osproxy.core.Target;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The real {@link Sink} + {@link Reader} over Helidon's pooled WebClient:
 * one client per configured cluster, blocking calls on virtual threads.
 * Operations dispatch individually ({@code PUT/POST/DELETE /_doc}); batching
 * into an upstream {@code _bulk} is a later optimization measured by JMH,
 * not assumed.
 */
public final class OpenSearchSink implements Sink, Reader {

    /** Upstream resilience knobs; {@link #DEFAULTS} suits a local cluster. */
    public record Resilience(
            Clock clock, long timeoutMillis, int breakerFailureThreshold, long breakerOpenMillis) {}

    /** 10s upstream timeout; open after 5 consecutive failures for 5s. */
    public static final Resilience DEFAULTS =
            new Resilience(new io.osproxy.core.SystemClock(), 10_000, 5, 5_000);

    private final Map<String, WebClient> clients = new ConcurrentHashMap<>();
    private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
    private final Map<ClusterId, String> endpoints;
    private final Resilience resilience;

    /** @param endpoints cluster id → base URL (e.g. {@code http://localhost:9200}) */
    public OpenSearchSink(Map<ClusterId, String> endpoints) {
        this(endpoints, DEFAULTS);
    }

    public OpenSearchSink(Map<ClusterId, String> endpoints, Resilience resilience) {
        this.endpoints = Map.copyOf(endpoints);
        this.resilience = resilience;
    }

    private String base(Target target) throws SinkException {
        return target.endpointOverride()
                .or(() -> Optional.ofNullable(endpoints.get(target.cluster())))
                .orElseThrow(() -> new SinkException(
                        ErrorCode.PLACEMENT_BACKEND_UNAVAILABLE,
                        "no endpoint configured for cluster " + target.cluster().value()));
    }

    private WebClient client(Target target) throws SinkException {
        String base = base(target);
        // Fail fast behind a dead upstream instead of queueing onto it.
        CircuitBreaker breaker = breakers.computeIfAbsent(base, b -> new CircuitBreaker(
                resilience.clock(),
                resilience.breakerFailureThreshold(),
                resilience.breakerOpenMillis() * 1_000_000));
        if (!breaker.allow()) {
            throw new SinkException(
                    ErrorCode.UPSTREAM_UNAVAILABLE, "circuit open for " + target.cluster().value());
        }
        return clients.computeIfAbsent(base, b -> WebClient.builder()
                .baseUri(b)
                .readTimeout(java.time.Duration.ofMillis(resilience.timeoutMillis()))
                .build());
    }

    /** The breaker for a target (reporting outcomes / diagnostics). */
    private CircuitBreaker breaker(Target target) throws SinkException {
        return breakers.get(base(target));
    }

    @Override
    public WriteBatch.Ack write(List<WriteBatch.Op> ops) throws SinkException {
        List<WriteBatch.OpResult> results = new ArrayList<>(ops.size());
        for (WriteBatch.Op op : ops) {
            results.add(dispatch(op));
        }
        return new WriteBatch.Ack(results);
    }

    private WriteBatch.OpResult dispatch(WriteBatch.Op op) throws SinkException {
        WebClient client = client(op.target());
        String index = op.target().index().value();
        // WebClient percent-encodes the path itself; hand it the raw id and
        // let queryParam carry the routing (pre-encoding double-encodes).
        String id = op.op().physicalId();
        try {
            HttpClientResponse response = switch (op.op()) {
                case DocOp.Index(var pid, byte[] doc, var r) ->
                        withRouting(client.put("/" + index + "/_doc/" + id), r)
                                .header(io.helidon.http.HeaderNames.CONTENT_TYPE, "application/json")
                                .submit(doc);
                case DocOp.Create(var pid, byte[] doc, var r) ->
                        withRouting(client.put("/" + index + "/_create/" + id), r)
                                .header(io.helidon.http.HeaderNames.CONTENT_TYPE, "application/json")
                                .submit(doc);
                case DocOp.Update(var pid, byte[] envelope, var r) ->
                        withRouting(client.post("/" + index + "/_update/" + id), r)
                                .header(io.helidon.http.HeaderNames.CONTENT_TYPE, "application/json")
                                .submit(envelope);
                case DocOp.Delete(var pid, var r) ->
                        withRouting(client.delete("/" + index + "/_doc/" + id), r).request();
            };
            try (response) {
                breaker(op.target()).onSuccess();
                String result = response.status().code() < 300 ? "ok" : "error";
                return new WriteBatch.OpResult(
                        response.status().code(), result, op.op().physicalId());
            }
        } catch (RuntimeException e) {
            breaker(op.target()).onFailure();
            throw new SinkException(ErrorCode.UPSTREAM_FAILED, "upstream write failed", e);
        }
    }

    @Override
    public Response get(Target target, String physicalId, Optional<String> routing)
            throws SinkException {
        WebClient client = client(target);
        return request(target, () -> withRouting(
                client.get("/" + target.index().value() + "/_doc/" + physicalId), routing)
                .request());
    }

    @Override
    public Response search(Target target, byte[] body) throws SinkException {
        WebClient client = client(target);
        return request(target, () -> client.post("/" + target.index().value() + "/_search")
                .header(io.helidon.http.HeaderNames.CONTENT_TYPE, "application/json")
                .submit(body.length == 0 ? "{}".getBytes(StandardCharsets.UTF_8) : body));
    }

    @Override
    public Response count(Target target, byte[] body) throws SinkException {
        WebClient client = client(target);
        return request(target, () -> client.post("/" + target.index().value() + "/_count")
                .header(io.helidon.http.HeaderNames.CONTENT_TYPE, "application/json")
                .submit(body.length == 0 ? "{}".getBytes(StandardCharsets.UTF_8) : body));
    }

    private interface Call {
        HttpClientResponse invoke() throws SinkException;
    }

    private Response request(Target target, Call call) throws SinkException {
        try {
            try (HttpClientResponse response = call.invoke()) {
                breaker(target).onSuccess();
                byte[] body = response.entity().hasEntity()
                        ? response.as(byte[].class)
                        : new byte[0];
                return new Response(response.status().code(), body);
            }
        } catch (RuntimeException e) {
            breaker(target).onFailure();
            throw new SinkException(ErrorCode.UPSTREAM_FAILED, "upstream read failed", e);
        }
    }

    /** Adds the routing query parameter when present. */
    private static <T extends io.helidon.webclient.api.ClientRequest<T>> T withRouting(
            T request, Optional<String> routing) {
        return routing.map(r -> request.queryParam("routing", r)).orElse(request);
    }
}
