package io.osproxy.sink;

import io.helidon.common.tls.Tls;
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
    private Optional<Tls> upstreamTls = Optional.empty();

    /** @param endpoints cluster id → base URL (e.g. {@code http://localhost:9200}) */
    public OpenSearchSink(Map<ClusterId, String> endpoints) {
        this(endpoints, DEFAULTS);
    }

    public OpenSearchSink(Map<ClusterId, String> endpoints, Resilience resilience) {
        this.endpoints = Map.copyOf(endpoints);
        this.resilience = resilience;
    }

    /**
     * Configures TLS for {@code https://} cluster endpoints. Applies to every
     * client this sink builds from here on; {@code Tls} carries the trusted
     * CA (and, for mutual TLS to the upstream, this proxy's own identity).
     */
    public OpenSearchSink withUpstreamTls(Tls tls) {
        this.upstreamTls = Optional.of(tls);
        return this;
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
        // Fail closed rather than silently falling back to the JDK's
        // platform trust store: an https:// cluster with no upstreamTls
        // configured means the operator hasn't decided what to trust yet.
        if (base.startsWith("https://") && upstreamTls.isEmpty()) {
            throw new SinkException(
                    ErrorCode.PLACEMENT_BACKEND_UNAVAILABLE,
                    "https upstream endpoint but no upstream TLS configured: " + base);
        }
        return clients.computeIfAbsent(base, b -> {
            var builder = WebClient.builder()
                    .baseUri(b)
                    .readTimeout(java.time.Duration.ofMillis(resilience.timeoutMillis()));
            if (b.startsWith("https://")) {
                builder.tls(upstreamTls.orElseThrow());
            }
            return builder.build();
        });
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
                        withRouting(traced(client.put("/" + index + "/_doc/" + id), op.target()), r)
                                .header(io.helidon.http.HeaderNames.CONTENT_TYPE, "application/json")
                                .submit(doc);
                case DocOp.Create(var pid, byte[] doc, var r) ->
                        withRouting(traced(client.put("/" + index + "/_create/" + id), op.target()), r)
                                .header(io.helidon.http.HeaderNames.CONTENT_TYPE, "application/json")
                                .submit(doc);
                case DocOp.Update(var pid, byte[] envelope, var r) ->
                        withRouting(traced(client.post("/" + index + "/_update/" + id), op.target()), r)
                                .header(io.helidon.http.HeaderNames.CONTENT_TYPE, "application/json")
                                .submit(envelope);
                case DocOp.Delete(var pid, var r) ->
                        withRouting(traced(client.delete("/" + index + "/_doc/" + id), op.target()), r)
                                .request();
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
    public WriteBatch.OpResult writeStreaming(
            Target target, boolean create, String physicalId,
            java.io.InputStream requestBody, StreamTransform transform, Optional<String> routing)
            throws SinkException {
        WebClient client = client(target);
        String index = target.index().value();
        var req = withRouting(
                traced(
                        client.put("/" + index + "/" + (create ? "_create" : "_doc") + "/" + physicalId),
                        target),
                routing)
                .header(io.helidon.http.HeaderNames.CONTENT_TYPE, "application/json");
        try (requestBody) {
            // transform runs right here, on this thread, reading requestBody
            // and writing straight into Helidon's upload stream — no pipe,
            // no second thread. requestBody is closed here regardless of
            // outcome; a transform is only obligated to read it, not close
            // it (io.osproxy.sink.StreamTransform.verbatim() doesn't).
            HttpClientResponse response = req.outputStream(os -> {
                transform.apply(requestBody, os);
                os.close();
            });
            try (response) {
                breaker(target).onSuccess();
                String result = response.status().code() < 300 ? "ok" : "error";
                return new WriteBatch.OpResult(response.status().code(), result, physicalId);
            }
        } catch (RuntimeException | java.io.IOException e) {
            breaker(target).onFailure();
            throw new SinkException(ErrorCode.UPSTREAM_FAILED, "upstream streaming write failed", e);
        }
    }

    @Override
    public Response get(Target target, String physicalId, Optional<String> routing)
            throws SinkException {
        WebClient client = client(target);
        return request(target, () -> withRouting(
                traced(client.get("/" + target.index().value() + "/_doc/" + physicalId), target),
                routing)
                .request());
    }

    @Override
    public Response search(Target target, byte[] body) throws SinkException {
        WebClient client = client(target);
        return request(target, () -> traced(client.post("/" + target.index().value() + "/_search"), target)
                .header(io.helidon.http.HeaderNames.CONTENT_TYPE, "application/json")
                .submit(body.length == 0 ? "{}".getBytes(StandardCharsets.UTF_8) : body));
    }

    @Override
    public Response count(Target target, byte[] body) throws SinkException {
        WebClient client = client(target);
        return request(target, () -> traced(client.post("/" + target.index().value() + "/_count"), target)
                .header(io.helidon.http.HeaderNames.CONTENT_TYPE, "application/json")
                .submit(body.length == 0 ? "{}".getBytes(StandardCharsets.UTF_8) : body));
    }

    @Override
    public Response searchStreaming(
            Target target, java.io.InputStream requestBody, StreamTransform transform)
            throws SinkException {
        return streamingQuery(target, "_search", requestBody, transform);
    }

    @Override
    public Response countStreaming(
            Target target, java.io.InputStream requestBody, StreamTransform transform)
            throws SinkException {
        return streamingQuery(target, "_count", requestBody, transform);
    }

    private Response streamingQuery(
            Target target, String suffix, java.io.InputStream requestBody, StreamTransform transform)
            throws SinkException {
        WebClient client = client(target);
        // transform runs right here, reading requestBody and writing
        // straight into Helidon's upload stream — no pipe, no second
        // thread; see writeStreaming's comment for why that matters.
        // requestBody is closed here regardless of outcome, same reasoning
        // as writeStreaming (a transform is only obligated to read it).
        try (requestBody) {
            return request(target, () ->
                    traced(client.post("/" + target.index().value() + "/" + suffix), target)
                            .header(io.helidon.http.HeaderNames.CONTENT_TYPE, "application/json")
                            .outputStream(os -> {
                                transform.apply(requestBody, os);
                                os.close();
                            }));
        } catch (java.io.IOException e) {
            throw new SinkException(ErrorCode.UPSTREAM_FAILED, "upstream streaming query failed", e);
        }
    }

    @Override
    public Response searchScroll(Target target, byte[] body, String scrollTtl)
            throws SinkException {
        WebClient client = client(target);
        return request(target, () -> traced(client.post("/" + target.index().value() + "/_search"), target)
                .queryParam("scroll", scrollTtl)
                .header(io.helidon.http.HeaderNames.CONTENT_TYPE, "application/json")
                .submit(body.length == 0 ? "{}".getBytes(StandardCharsets.UTF_8) : body));
    }

    @Override
    public Response scrollNext(Target target, byte[] body) throws SinkException {
        WebClient client = client(target);
        return request(target, () -> traced(client.post("/_search/scroll"), target)
                .header(io.helidon.http.HeaderNames.CONTENT_TYPE, "application/json")
                .submit(body));
    }

    @Override
    public Response scrollDelete(Target target, byte[] body) throws SinkException {
        WebClient client = client(target);
        return request(target, () -> traced(client.method(io.helidon.http.Method.DELETE), target)
                .path("/_search/scroll")
                .header(io.helidon.http.HeaderNames.CONTENT_TYPE, "application/json")
                .submit(body));
    }

    @Override
    public Response pitOpen(Target target, String keepAlive) throws SinkException {
        WebClient client = client(target);
        return request(target, () -> traced(client
                .post("/" + target.index().value() + "/_search/point_in_time"), target)
                .queryParam("keep_alive", keepAlive)
                .request());
    }

    @Override
    public Response pitClose(Target target, byte[] body) throws SinkException {
        WebClient client = client(target);
        return request(target, () -> traced(client.method(io.helidon.http.Method.DELETE), target)
                .path("/_search/point_in_time")
                .header(io.helidon.http.HeaderNames.CONTENT_TYPE, "application/json")
                .submit(body));
    }

    @Override
    public Response searchIndexless(Target target, byte[] body) throws SinkException {
        WebClient client = client(target);
        return request(target, () -> traced(client.post("/_search"), target)
                .header(io.helidon.http.HeaderNames.CONTENT_TYPE, "application/json")
                .submit(body.length == 0 ? "{}".getBytes(StandardCharsets.UTF_8) : body));
    }

    @Override
    public Response forward(
            Target target, io.osproxy.spi.RequestCtx.HttpMethod method, String path,
            String query, byte[] body, java.util.List<Map.Entry<String, String>> extraHeaders)
            throws SinkException {
        WebClient client = client(target);
        return request(target, () -> {
            var req = forwardRequest(client, target, method, path, query, extraHeaders);
            return body.length == 0 ? req.request() : req.submit(body);
        });
    }

    @Override
    public StreamedResponse forwardStreaming(
            Target target, io.osproxy.spi.RequestCtx.HttpMethod method, String path,
            String query, java.io.InputStream requestBody,
            java.util.List<Map.Entry<String, String>> extraHeaders)
            throws SinkException {
        WebClient client = client(target);
        var req = forwardRequest(client, target, method, path, query, extraHeaders);
        try {
            // Piped straight through: this handler runs on the calling
            // (virtual) thread as the request is written, so no intermediate
            // buffer ever holds the whole body.
            HttpClientResponse response = req.outputStream(os -> {
                requestBody.transferTo(os);
                os.close();
            });
            breaker(target).onSuccess();
            return new StreamedResponse(response.status().code(), response.inputStream(), response);
        } catch (RuntimeException e) {
            breaker(target).onFailure();
            throw new SinkException(ErrorCode.UPSTREAM_FAILED, "upstream forward failed", e);
        }
    }

    /** Builds the shared verbatim-forward request (method/path/query/headers/trace). */
    private static io.helidon.webclient.api.HttpClientRequest forwardRequest(
            WebClient client, Target target, io.osproxy.spi.RequestCtx.HttpMethod method, String path,
            String query, java.util.List<Map.Entry<String, String>> extraHeaders) {
        io.helidon.http.Method helidonMethod = switch (method) {
            case GET -> io.helidon.http.Method.GET;
            case PUT -> io.helidon.http.Method.PUT;
            case POST -> io.helidon.http.Method.POST;
            case DELETE -> io.helidon.http.Method.DELETE;
            case HEAD -> io.helidon.http.Method.HEAD;
        };
        var req = traced(client.method(helidonMethod), target).path(path);
        if (query != null && !query.isEmpty()) {
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq >= 0) {
                    req = req.queryParam(pair.substring(0, eq), pair.substring(eq + 1));
                }
            }
        }
        for (Map.Entry<String, String> header : extraHeaders) {
            req = req.header(io.helidon.http.HeaderNames.create(header.getKey()), header.getValue());
        }
        return req;
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

    /**
     * Injects the current request's {@code traceparent} when a trace is
     * bound, the client's forwarded header set when one is bound, and the
     * target's own upstream credential when its {@code TenancySpi} supplied
     * one — the one choke point where the proxy's causal chain and (when
     * configured) auth reach the upstream, for every call this sink makes
     * (write, read, cursor, and verbatim forward alike). No header plumbing
     * through the engine for the first two: the ingress binds
     * {@link io.osproxy.core.ForwardHeaders#CURRENT} once per request, this
     * is the only place that reads it.
     *
     * <p>Order matters: forwarded headers first, then the target's
     * credential (so an SPI-supplied credential overwrites a same-named
     * header the client forwarded — the proxy's own upstream identity is
     * deliberate, not passthrough, when both are configured), then the
     * proxy's own {@code traceparent} last (pass-all forwarding carries the
     * client's own {@code traceparent} through like any other header, but
     * the proxy's minted child-span value is the one that must reach the
     * upstream so the causal chain stays intact).
     */
    private static <T extends io.helidon.webclient.api.ClientRequest<T>> T traced(
            T request, Target target) {
        for (Map.Entry<String, String> header : io.osproxy.core.ForwardHeaders.currentOrEmpty()) {
            request.header(io.helidon.http.HeaderNames.create(header.getKey()), header.getValue());
        }
        target.credentials().ifPresent(creds -> request.header(
                io.helidon.http.HeaderNames.create(creds.headerName()), creds.headerValue()));
        if (io.osproxy.core.Tracing.CURRENT.isBound()) {
            request.header(
                    io.helidon.http.HeaderNames.create("traceparent"),
                    io.osproxy.core.Tracing.CURRENT.get().toTraceparent());
        }
        return request;
    }

    /** Adds the routing query parameter when present. */
    private static <T extends io.helidon.webclient.api.ClientRequest<T>> T withRouting(
            T request, Optional<String> routing) {
        return routing.map(r -> request.queryParam("routing", r)).orElse(request);
    }
}
