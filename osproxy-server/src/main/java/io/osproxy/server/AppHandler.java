package io.osproxy.server;

import io.helidon.http.HeaderNames;
import io.helidon.http.Status;
import io.helidon.webserver.http.HttpRouting;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;
import io.osproxy.core.ErrorCode;
import io.osproxy.engine.Classify;
import io.osproxy.engine.Pipeline;
import io.osproxy.engine.PipelineResponse;
import io.osproxy.spi.Principal;
import io.osproxy.spi.RequestCtx;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Helidon ingress: authenticate → classify → pipeline → respond. Every
 * path funnels through the one handler; the pipeline decides what is
 * supported. Runs on virtual threads (Helidon SE 4's default), so the
 * blocking pipeline call is idiomatic.
 */
public final class AppHandler {

    /** The pre-auth introspection paths (shape-only, safe to leave on). */
    public static final String METRICS_PATH = "/_osproxy/metrics";

    /** Prefix of the explain lookup: {@code /_osproxy/explain/<request-id>}. */
    public static final String EXPLAIN_PREFIX = "/_osproxy/explain/";

    /** Echoed on every response so a client can look its request up. */
    public static final String REQUEST_ID_HEADER = "x-osproxy-request-id";

    /** The token-gated directive publish/introspect endpoint. */
    public static final String ADMIN_DIRECTIVES_PATH = "/_osproxy/admin/directives";

    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    private final Pipeline pipeline;
    private final BearerAuth auth;
    private final long maxBodyBytes;
    private final boolean requireTlsForMutation;
    private final Observability observability;
    private Optional<String> adminToken = Optional.empty();

    public AppHandler(Pipeline pipeline, BearerAuth auth) {
        this(pipeline, auth, io.osproxy.config.ProxyConfig.DEFAULT_MAX_BODY_BYTES, false);
    }

    public AppHandler(
            Pipeline pipeline, BearerAuth auth, long maxBodyBytes, boolean requireTlsForMutation) {
        this(pipeline, auth, maxBodyBytes, requireTlsForMutation,
                new Observability(512, Optional.empty()));
    }

    public AppHandler(
            Pipeline pipeline, BearerAuth auth, long maxBodyBytes,
            boolean requireTlsForMutation, Observability observability) {
        this.pipeline = pipeline;
        this.auth = auth;
        this.maxBodyBytes = maxBodyBytes;
        this.requireTlsForMutation = requireTlsForMutation;
        this.observability = observability;
    }

    /** Enables the directive admin endpoint, gated by this bearer token. */
    public AppHandler withAdminToken(String token) {
        this.adminToken = Optional.of(token);
        return this;
    }

    /** Installs the catch-all route. */
    public void route(HttpRouting.Builder routing) {
        routing.any(this::handleTraced);
    }

    /** Binds the request's trace context, then handles. */
    private void handleTraced(ServerRequest req, ServerResponse res) {
        io.osproxy.core.TraceContext trace = req.headers()
                .first(HeaderNames.create("traceparent"))
                .flatMap(io.osproxy.core.TraceContext::parse)
                .map(incoming -> incoming.child(randomBytes(8)))
                .orElseGet(() -> io.osproxy.core.TraceContext.mint(
                        randomBytes(16), randomBytes(8)));
        ScopedValue.where(io.osproxy.core.Tracing.CURRENT, trace)
                .run(() -> handle(req, res));
    }

    private static byte[] randomBytes(int n) {
        byte[] bytes = new byte[n];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private void handle(ServerRequest req, ServerResponse res) {
        // Introspection short-circuits before auth: shape-only surfaces that
        // stay readable even when the data plane's credentials are broken.
        String rawPath = req.path().rawPath();
        if (rawPath.equals(METRICS_PATH)) {
            res.status(Status.OK_200)
                    .header(HeaderNames.CONTENT_TYPE, "application/json")
                    .send(observability.metrics().toJson());
            return;
        }
        if (rawPath.equals(ADMIN_DIRECTIVES_PATH)) {
            handleAdminDirectives(req, res);
            return;
        }
        if (rawPath.startsWith(EXPLAIN_PREFIX)) {
            String id = rawPath.substring(EXPLAIN_PREFIX.length());
            var doc = observability.explainStore().lookup(id);
            res.status(doc.isPresent() ? Status.OK_200 : Status.NOT_FOUND_404)
                    .header(HeaderNames.CONTENT_TYPE, "application/json")
                    .send(doc.map(io.osproxy.observe.ExplainDoc::toJson)
                            .orElse("{\"error\":\"unknown_request_id\"}"));
            return;
        }
        Optional<RequestCtx.HttpMethod> method = method(req.prologue().method().text());
        if (method.isEmpty()) {
            send(res, PipelineResponse.error(ErrorCode.UNSUPPORTED_ENDPOINT));
            return;
        }

        Optional<Principal> principal = auth.authenticate(
                req.headers().first(HeaderNames.AUTHORIZATION),
                req.headers().first(HeaderNames.create("x-tenant")));
        if (principal.isEmpty()) {
            send(res, PipelineResponse.error(ErrorCode.AUTH_FAILED));
            return;
        }

        String path = req.path().rawPath();
        Classify.Classified classified = Classify.classify(method.get(), path);

        // NFR-S1: a body-mutating request must arrive over TLS when required.
        if (requireTlsForMutation && classified.endpoint().isWrite() && !req.isSecure()) {
            send(res, PipelineResponse.error(ErrorCode.UNAUTHORIZED));
            return;
        }

        // Bound the working set before buffering: over-cap bodies are refused
        // up front (fail-closed), not allocated for.
        long declared = req.headers()
                .first(HeaderNames.CONTENT_LENGTH)
                .map(Long::parseLong)
                .orElse(0L);
        if (declared > maxBodyBytes) {
            send(res, PipelineResponse.error(ErrorCode.PAYLOAD_TOO_LARGE));
            return;
        }
        byte[] body = req.content().hasEntity() ? req.content().as(byte[].class) : new byte[0];
        if (body.length > maxBodyBytes) {
            send(res, PipelineResponse.error(ErrorCode.PAYLOAD_TOO_LARGE));
            return;
        }

        List<Map.Entry<String, String>> headers = new ArrayList<>();
        req.headers().forEach(h -> headers.add(Map.entry(h.name(), h.values())));

        RequestCtx ctx = new RequestCtx(
                method.get(), path, classified.endpoint(),
                classified.logicalIndex(), classified.docId(),
                headers, body, principal.get(),
                req.query().rawValue());

        long started = System.nanoTime();
        PipelineResponse out = pipeline.handle(ctx);
        record(ctx, res, out, System.nanoTime() - started);
        send(res, out);
    }

    /** Publish (POST) / introspect (GET) the directive set, token-gated. */
    private void handleAdminDirectives(ServerRequest req, ServerResponse res) {
        // Fail closed: no configured token means the endpoint does not exist.
        if (adminToken.isEmpty()) {
            send(res, PipelineResponse.error(ErrorCode.UNSUPPORTED_ENDPOINT));
            return;
        }
        boolean authorized = req.headers().first(HeaderNames.AUTHORIZATION)
                .filter(h -> h.regionMatches(true, 0, "Bearer ", 0, 7))
                .map(h -> h.substring(7).strip())
                .map(t -> java.security.MessageDigest.isEqual(
                        t.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        adminToken.get().getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .orElse(false);
        if (!authorized) {
            send(res, PipelineResponse.error(ErrorCode.AUTH_FAILED));
            return;
        }
        var api = new DirectivesApi(observability.clock());
        var store = observability.directives();
        String method = req.prologue().method().text();
        if (method.equals("GET")) {
            res.status(Status.OK_200)
                    .header(HeaderNames.CONTENT_TYPE, "application/json")
                    .send(api.introspect(store.load()));
            return;
        }
        if (!method.equals("POST")
                || !(store instanceof io.osproxy.observe.DirectiveSet.InMemoryStore publishable)) {
            send(res, PipelineResponse.error(ErrorCode.UNSUPPORTED_ENDPOINT));
            return;
        }
        byte[] body = req.content().hasEntity() ? req.content().as(byte[].class) : new byte[0];
        try {
            publishable.publish(api.decode(body));
            res.status(Status.OK_200)
                    .header(HeaderNames.CONTENT_TYPE, "application/json")
                    .send("{\"published\":true}");
        } catch (DirectivesApi.InvalidDirectives e) {
            send(res, PipelineResponse.error(ErrorCode.MALFORMED_REQUEST));
        }
    }

    /** Tallies the completed request and echoes its id. */
    private void record(
            RequestCtx ctx, ServerResponse res, PipelineResponse out, long durationNanos) {
        String requestId = java.util.HexFormat.of().formatHex(randomBytes(8));
        res.header(HeaderNames.create(REQUEST_ID_HEADER), requestId);
        Optional<String> errorCode = out.status() >= 400
                ? extractErrorCode(out.body())
                : Optional.empty();
        observability.record(
                new io.osproxy.observe.ExplainDoc(
                        requestId,
                        io.osproxy.core.Tracing.CURRENT.get().traceId(),
                        ctx.endpoint(),
                        ctx.method().name(),
                        out.status(),
                        errorCode,
                        durationNanos),
                new io.osproxy.observe.Directive.RequestAttrs(
                        ctx.principal().attribute("tenant").orElse(""),
                        ctx.logicalIndex(),
                        ctx.endpoint()));
    }

    /** Pulls the wire code out of a shape-only error body. */
    private static Optional<String> extractErrorCode(byte[] body) {
        String text = new String(body, java.nio.charset.StandardCharsets.UTF_8);
        int at = text.indexOf("\"error\":\"");
        if (at < 0) {
            return Optional.empty();
        }
        int start = at + 9;
        int end = text.indexOf('"', start);
        return end > start ? Optional.of(text.substring(start, end)) : Optional.empty();
    }

    private static void send(ServerResponse res, PipelineResponse out) {
        res.status(Status.create(out.status()))
                .header(HeaderNames.CONTENT_TYPE, "application/json")
                .send(out.body());
    }

    private static Optional<RequestCtx.HttpMethod> method(String name) {
        return switch (name) {
            case "GET" -> Optional.of(RequestCtx.HttpMethod.GET);
            case "PUT" -> Optional.of(RequestCtx.HttpMethod.PUT);
            case "POST" -> Optional.of(RequestCtx.HttpMethod.POST);
            case "DELETE" -> Optional.of(RequestCtx.HttpMethod.DELETE);
            case "HEAD" -> Optional.of(RequestCtx.HttpMethod.HEAD);
            default -> Optional.empty();
        };
    }
}
