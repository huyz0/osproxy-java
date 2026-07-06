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

    /** The break-glass tape read: recent explanations captured on demand. */
    public static final String BREAKGLASS_PATH = "/_osproxy/breakglass";

    /** The opt-in per-tenant counters (Prometheus text), when enabled. */
    public static final String TENANT_METRICS_PATH = "/_osproxy/metrics/tenants";

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
    private Optional<io.osproxy.capture.Capture> capture = Optional.empty();
    private boolean debugEndpoints = true;
    private ForwardPolicy forwardPolicy = ForwardPolicy.passAll();

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

    /**
     * Enables full-fidelity traffic capture. Composed through {@code
     * Capture.safe} here (not left to the caller) so a broken capture
     * backend can never fail the request it's capturing; the header
     * redaction still happens at the call site below, which is the actual
     * choke point every capturing code path already goes through.
     */
    public AppHandler withCapture(io.osproxy.capture.Capture capture) {
        this.capture = Optional.of(io.osproxy.capture.Capture.safe(capture));
        return this;
    }

    /** Enables the directive admin endpoint, gated by this bearer token. */
    public AppHandler withAdminToken(String token) {
        this.adminToken = Optional.of(token);
        return this;
    }

    /**
     * Toggles the shape-only {@code /_osproxy/explain} and
     * {@code /_osproxy/breakglass} surfaces (default: on). Off in production
     * so operational metadata is not exposed unauthenticated; disabled
     * requests report {@code not_enabled} rather than 404, to distinguish
     * "turned off here" from "no such route". {@code /_osproxy/metrics}
     * always stays on regardless of this switch.
     */
    public AppHandler withDebugEndpoints(boolean enabled) {
        this.debugEndpoints = enabled;
        return this;
    }

    /**
     * Sets the client-header-forwarding policy (default: {@link
     * ForwardPolicy#passAll()}, the sidecar-trust default — every client
     * header rides through to the upstream except the mandatory
     * hop-by-hop/framing set). Applies to every upstream call the sink
     * makes for this request — write, read, cursor, and verbatim forward
     * alike — via {@link io.osproxy.core.ForwardHeaders}, bound once per
     * request and read at the sink's one upstream choke point.
     */
    public AppHandler withForwardPolicy(ForwardPolicy policy) {
        this.forwardPolicy = policy;
        return this;
    }

    /** Installs the catch-all route. */
    public void route(HttpRouting.Builder routing) {
        routing.any(this::handleTraced);
    }

    /** Binds the request's trace context and forwarded-header set, then handles. */
    private void handleTraced(ServerRequest req, ServerResponse res) {
        List<Map.Entry<String, String>> rawHeaders = new ArrayList<>();
        req.headers().forEach(h -> rawHeaders.add(Map.entry(h.name(), h.values())));
        traced(rawHeaders, req.headers().first(HeaderNames.create("traceparent")),
                () -> handle(req, res));
    }

    /**
     * Binds trace context and the forwarded-header set from {@code
     * rawHeaders}/{@code incomingTraceparent}, then runs {@code body}. Every
     * ingress protocol needs this around its dispatch: {@link #dispatch}
     * reads {@code Tracing.CURRENT} (via {@code recordCompletion}) and the
     * sink reads both scoped values at its upstream choke point, so an
     * ingress that skips this binding gets an unbound-{@code ScopedValue}
     * failure the first time it calls {@link #dispatch}. REST goes through
     * {@link #handleTraced} above; {@link GrpcDocumentService} calls this
     * directly, since a gRPC RPC has no {@code ServerRequest} to read a
     * {@code traceparent} header off.
     */
    <T> T traced(
            List<Map.Entry<String, String>> rawHeaders, Optional<String> incomingTraceparent,
            java.util.function.Supplier<T> body) {
        io.osproxy.core.TraceContext trace = incomingTraceparent
                .flatMap(io.osproxy.core.TraceContext::parse)
                .map(incoming -> incoming.child(randomBytes(8)))
                .orElseGet(() -> io.osproxy.core.TraceContext.mint(
                        randomBytes(16), randomBytes(8)));
        List<Map.Entry<String, String>> forward = forwardPolicy.forwardSet(rawHeaders);
        return ScopedValue.where(io.osproxy.core.Tracing.CURRENT, trace)
                .where(io.osproxy.core.ForwardHeaders.CURRENT, forward)
                .call(() -> body.get());
    }

    private void traced(List<Map.Entry<String, String>> rawHeaders, Optional<String> incomingTraceparent, Runnable body) {
        traced(rawHeaders, incomingTraceparent, () -> {
            body.run();
            return null;
        });
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
        if (rawPath.equals(BREAKGLASS_PATH)) {
            if (!debugEndpoints) {
                sendNotEnabled(res);
                return;
            }
            List<String> tape = observability.breakGlass().snapshot();
            res.status(Status.OK_200)
                    .header(HeaderNames.CONTENT_TYPE, "application/json")
                    .send("[" + String.join(",", tape) + "]");
            return;
        }
        if (rawPath.equals(TENANT_METRICS_PATH)) {
            // Unlike METRICS_PATH, this carries tenant identifiers, so it is
            // gated the same way as the other operational-metadata surfaces
            // (debugEndpoints) rather than always staying on.
            if (!debugEndpoints || observability.tenantMetrics().isEmpty()) {
                sendNotEnabled(res);
                return;
            }
            res.status(Status.OK_200)
                    .header(HeaderNames.CONTENT_TYPE, "text/plain; version=0.0.4")
                    .send(observability.tenantMetrics().get().toPrometheusText());
            return;
        }
        if (rawPath.startsWith(EXPLAIN_PREFIX)) {
            if (!debugEndpoints) {
                sendNotEnabled(res);
                return;
            }
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

        // Tenant-agnostic passthrough gets a true streaming path: neither
        // direction is ever buffered as a byte[], so a passthrough request
        // is not bound by maxBodyBytes at all — the whole point of streaming
        // is escaping that cap for legitimately large bodies. Checked before
        // any buffering happens, using only the classification already done.
        Optional<io.osproxy.engine.PassthroughPolicy> passthrough = pipeline.passthroughPolicy()
                .filter(p -> p.matchesIndex(classified.logicalIndex().orElse("")));
        if (passthrough.isPresent()) {
            streamPassthrough(req, res, classified, method.get(), principal.get(), passthrough.get());
            return;
        }

        // _bulk gets the same treatment for the same reason: its transform
        // is per-item (inject/construct-id), so nothing about it needs the
        // whole body in memory at once — only the buffered read imposed that.
        // Excluded in async mode for the same reason single-doc ingest is
        // below: streamBulk's streaming twin (bulkStreaming) dispatches
        // straight to the upstream sink with no async handling of its own,
        // so an async-mode bulk routed here would silently skip the
        // durable-enqueue contract.
        if (classified.endpoint() == io.osproxy.core.EndpointKind.INGEST_BULK
                && !io.osproxy.engine.AsyncWrites.wantsAsync(
                        req.headers().first(HeaderNames.create(
                                io.osproxy.engine.AsyncWrites.WRITE_MODE_HEADER)))) {
            streamBulk(req, res, classified, method.get(), principal.get());
            return;
        }

        // Single-doc ingest streams too, but only when the physical target
        // and id don't require reading the document first (see
        // Pipeline#supportsStreamingIngest) — that's a property of the
        // tenancy configuration, checked once per request, not the body —
        // and only when the request isn't async-mode: ingestDocStreaming
        // writes straight to the upstream sink with no async handling of
        // its own, so an async-mode request routed here would silently
        // skip the durable-enqueue contract and dial the real upstream
        // instead of the queue.
        if (classified.endpoint() == io.osproxy.core.EndpointKind.INGEST_DOC
                && pipeline.supportsStreamingIngest()
                && !io.osproxy.engine.AsyncWrites.wantsAsync(
                        req.headers().first(HeaderNames.create(
                                io.osproxy.engine.AsyncWrites.WRITE_MODE_HEADER)))) {
            streamIngest(req, res, classified, method.get(), principal.get());
            return;
        }

        // Search/count stream too, for the ordinary index-present case:
        // scroll-open and PIT search both need the buffered body for their
        // own reasons (opening a cursor, or the index-less pit-clause
        // sniff), so they're excluded here and fall through to the
        // buffered path below like every other endpoint.
        if ((classified.endpoint() == io.osproxy.core.EndpointKind.SEARCH
                || classified.endpoint() == io.osproxy.core.EndpointKind.COUNT)
                && classified.logicalIndex().isPresent()
                && !req.query().contains("scroll")) {
            streamSearch(req, res, classified, method.get(), principal.get());
            return;
        }

        // Bound the working set before buffering. A declared over-cap length
        // refuses immediately; a chunked body (no Content-Length) is read
        // incrementally and cut off the moment it crosses the cap, so the
        // bound holds without trusting the client to declare anything.
        long declared;
        try {
            declared = req.headers()
                    .first(HeaderNames.CONTENT_LENGTH)
                    .map(Long::parseLong)
                    .orElse(-1L);
        } catch (NumberFormatException e) {
            send(res, PipelineResponse.error(ErrorCode.MALFORMED_REQUEST));
            return;
        }
        if (declared > maxBodyBytes) {
            send(res, PipelineResponse.error(ErrorCode.PAYLOAD_TOO_LARGE));
            return;
        }
        byte[] body;
        try {
            body = hasBody(req)
                    ? readCapped(req.content().inputStream())
                    : new byte[0];
        } catch (OverCapException e) {
            send(res, PipelineResponse.error(ErrorCode.PAYLOAD_TOO_LARGE));
            return;
        } catch (java.io.IOException e) {
            send(res, PipelineResponse.error(ErrorCode.MALFORMED_REQUEST));
            return;
        }

        List<Map.Entry<String, String>> headers = new ArrayList<>();
        req.headers().forEach(h -> headers.add(Map.entry(h.name(), h.values())));

        RequestCtx ctx = new RequestCtx(
                method.get(), path, classified.endpoint(),
                classified.logicalIndex(), classified.docId(),
                headers, body, principal.get(),
                req.query().rawValue());

        Dispatched dispatched = dispatch(ctx);
        res.header(HeaderNames.create(REQUEST_ID_HEADER), dispatched.requestId());
        send(res, dispatched.response());
    }

    /** What {@link #dispatch} produced: the pipeline's response and the request id it was recorded under. */
    public record Dispatched(PipelineResponse response, String requestId) {}

    /**
     * The transport-agnostic core every ingress protocol shares once it has
     * built a {@link RequestCtx}: run the pipeline, record the completion
     * (explain doc, metrics), and capture, in that order. REST's buffered
     * path above is one caller; {@link GrpcDocumentService} is another,
     * since a gRPC RPC has no {@code ServerResponse} to hang a header off.
     */
    public Dispatched dispatch(RequestCtx ctx) {
        long started = System.nanoTime();
        PipelineResponse out = pipeline.handle(ctx);
        String requestId = recordCompletion(ctx, out, System.nanoTime() - started);
        // Credentials never reach a capture backend, whatever the sink is:
        // redaction happens here at the choke point, not by caller discipline.
        // safe(...) wraps redacting(c) itself (not just c) so a failure in
        // the redaction step can't escape either — this.capture is already
        // safe-wrapped, but that only guards the delegate, not this mapping.
        capture.ifPresent(c -> io.osproxy.capture.Capture.safe(io.osproxy.capture.Capture.redacting(c))
                .capture(new io.osproxy.capture.Capture.Record(
                        ctx.method().name(), ctx.path(), ctx.headers(), ctx.body(),
                        out.status(), out.body())));
        return new Dispatched(out, requestId);
    }

    /**
     * Forwards a passthrough-matched request with neither direction ever
     * buffered: the client's request body is piped straight to the upstream
     * as it arrives, and the upstream's response is piped straight back —
     * {@link io.osproxy.sink.Reader#forwardStreaming} does the same for the
     * sink side. Credentials and captured bytes never enter this path
     * (capture and body-based redaction need the bytes, which streaming
     * deliberately never materializes); that is the trade a passthrough
     * deployment makes for handling arbitrarily large bodies.
     */
    private void streamPassthrough(
            ServerRequest req, ServerResponse res, Classify.Classified classified,
            RequestCtx.HttpMethod method, Principal principal,
            io.osproxy.engine.PassthroughPolicy policy) {
        long started = System.nanoTime();
        String requestId = java.util.HexFormat.of().formatHex(randomBytes(8));
        // Tracks the status once the upstream responded, so a failure while
        // streaming the body back (after headers already committed) is still
        // recorded under the status the client actually saw, not silently
        // dropped from observability.
        int[] committedStatus = {-1};
        try (java.io.InputStream reqBody = hasBody(req)
                ? req.content().inputStream() : java.io.InputStream.nullInputStream()) {
            try (var streamed = pipeline.reader().forwardStreaming(
                    policy.target(), method, req.path().rawPath(), req.query().rawValue(),
                    reqBody, List.of())) {
                committedStatus[0] = streamed.status();
                res.status(Status.create(streamed.status()))
                        .header(HeaderNames.create(REQUEST_ID_HEADER), requestId);
                try (var out = res.outputStream()) {
                    streamed.body().transferTo(out);
                }
                recordCompletion(requestId, classified, method, principal,
                        streamed.status(), Optional.empty(), System.nanoTime() - started);
            }
        } catch (Exception e) {
            boolean committed = committedStatus[0] >= 0;
            int status = committed ? committedStatus[0] : ErrorCode.UPSTREAM_FAILED.httpStatus();
            Optional<String> errorCode = committed
                    ? Optional.empty() : Optional.of(ErrorCode.UPSTREAM_FAILED.wireName());
            recordCompletion(requestId, classified, method, principal,
                    status, errorCode, System.nanoTime() - started);
            if (!res.isSent()) {
                send(res, PipelineResponse.error(ErrorCode.UPSTREAM_FAILED));
            }
        }
    }

    /**
     * Streams a single-doc ingest: the client body is piped through {@link
     * Pipeline#ingestDocStreaming}'s token-level field injection straight to
     * the upstream request, so the document is never buffered as a byte[]
     * or a Jackson tree. Unlike {@link #streamBulk}/{@link
     * #streamPassthrough}, {@code maxBodyBytes} still applies here, wrapped
     * onto the input via {@link CappingInputStream}: that cap is a
     * pre-existing resource-protection guarantee for the single-doc case
     * specifically, and this change doesn't get to silently drop it just
     * because streaming makes it technically possible to. The win is still
     * real — documents up to the cap no longer cost a buffer copy plus a
     * full tree materialization, just the one streaming pass. The response
     * is the normal small ack every ingest returns; only the request body
     * streams, so any failure (over cap, malformed body, upstream trouble)
     * still gets an ordinary error status, since nothing is written to the
     * client until the whole upload finishes.
     */
    private void streamIngest(
            ServerRequest req, ServerResponse res, Classify.Classified classified,
            RequestCtx.HttpMethod method, Principal principal) {
        List<Map.Entry<String, String>> headers = new ArrayList<>();
        req.headers().forEach(h -> headers.add(Map.entry(h.name(), h.values())));
        RequestCtx ctx = new RequestCtx(
                method, req.path().rawPath(), classified.endpoint(),
                classified.logicalIndex(), classified.docId(),
                headers, new byte[0], principal, req.query().rawValue());
        long started = System.nanoTime();
        try (java.io.InputStream in = hasBody(req)
                ? new CappingInputStream(req.content().inputStream(), maxBodyBytes)
                : java.io.InputStream.nullInputStream()) {
            PipelineResponse out = pipeline.ingestDocStreaming(ctx, in);
            record(ctx, res, out, System.nanoTime() - started);
            send(res, out);
        } catch (io.osproxy.spi.SpiException e) {
            recordAndSend(ctx, res, e.errorCode(), System.nanoTime() - started);
        } catch (io.osproxy.rewrite.RewriteException e) {
            recordAndSend(ctx, res, ErrorCode.MALFORMED_REQUEST, System.nanoTime() - started);
        } catch (io.osproxy.sink.SinkException e) {
            recordAndSend(ctx, res, e.errorCode(), System.nanoTime() - started);
        } catch (Exception e) {
            recordAndSend(ctx, res, ErrorCode.UPSTREAM_FAILED, System.nanoTime() - started);
        }
    }

    /** Records a failure (so streaming failures are as observable as the buffered path's) and sends it. */
    private void recordAndSend(RequestCtx ctx, ServerResponse res, ErrorCode code, long durationNanos) {
        PipelineResponse err = PipelineResponse.error(code);
        record(ctx, res, err, durationNanos);
        send(res, err);
    }

    /**
     * Streams a search/count: the client body is piped through {@link
     * Pipeline#searchStreaming}'s token-level query-wrapping transform
     * straight to the upstream request, so the query is never buffered as a
     * byte[] or a Jackson tree — except the {@code aggs}/{@code
     * aggregations} clause, which the transform reads as a tree to run the
     * unfilterable check (see {@code Queries.wrapQueryStreaming}). Like
     * {@link #streamIngest}, {@code maxBodyBytes} still applies (same
     * {@link CappingInputStream}): the cap protects against one oversized
     * request body, and search bodies are ordinarily small queries, not the
     * legitimately-large aggregate payloads passthrough/{@code _bulk} exist
     * to escape. The response is the ordinary buffered one (shaping needs
     * the whole tree); only the request body streams.
     */
    private void streamSearch(
            ServerRequest req, ServerResponse res, Classify.Classified classified,
            RequestCtx.HttpMethod method, Principal principal) {
        List<Map.Entry<String, String>> headers = new ArrayList<>();
        req.headers().forEach(h -> headers.add(Map.entry(h.name(), h.values())));
        RequestCtx ctx = new RequestCtx(
                method, req.path().rawPath(), classified.endpoint(),
                classified.logicalIndex(), classified.docId(),
                headers, new byte[0], principal, req.query().rawValue());
        long started = System.nanoTime();
        boolean search = classified.endpoint() == io.osproxy.core.EndpointKind.SEARCH;
        try (java.io.InputStream in = hasBody(req)
                ? new CappingInputStream(req.content().inputStream(), maxBodyBytes)
                : java.io.InputStream.nullInputStream()) {
            PipelineResponse out = pipeline.searchStreaming(ctx, in, search);
            record(ctx, res, out, System.nanoTime() - started);
            send(res, out);
        } catch (io.osproxy.spi.SpiException e) {
            recordAndSend(ctx, res, e.errorCode(), System.nanoTime() - started);
        } catch (io.osproxy.rewrite.RewriteException e) {
            recordAndSend(ctx, res, ErrorCode.MALFORMED_REQUEST, System.nanoTime() - started);
        } catch (io.osproxy.sink.SinkException e) {
            recordAndSend(ctx, res, e.errorCode(), System.nanoTime() - started);
        } catch (Exception e) {
            recordAndSend(ctx, res, ErrorCode.UPSTREAM_FAILED, System.nanoTime() - started);
        }
    }

    /** Throws {@link io.osproxy.core.BodyTooLargeException} once more than {@code cap} bytes are read. */
    private static final class CappingInputStream extends java.io.FilterInputStream {
        private final long cap;
        private long seen;

        CappingInputStream(java.io.InputStream in, long cap) {
            super(in);
            this.cap = cap;
        }

        @Override
        public int read() throws java.io.IOException {
            int b = super.read();
            if (b >= 0) {
                check(1);
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws java.io.IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                check(n);
            }
            return n;
        }

        private void check(int n) throws java.io.IOException {
            seen += n;
            if (seen > cap) {
                throw new io.osproxy.core.BodyTooLargeException(
                        "streamed ingest body exceeded the configured cap");
            }
        }
    }

    /**
     * Streams a tenanted {@code _bulk} request: parses and dispatches one
     * NDJSON item at a time from the client's raw request stream, writing
     * each result to the client as it completes — so, unlike every other
     * tenanted endpoint, {@code _bulk} is not bound by {@code maxBodyBytes}
     * either. {@link Pipeline#openBulkStream} validates the body isn't empty
     * and its first line parses before anything is committed, so those two
     * cases still get a normal error status; any failure after that point
     * can only truncate the already-streamed response, since 200 is already
     * committed by then (the same trade {@link #streamPassthrough} makes).
     */
    private void streamBulk(
            ServerRequest req, ServerResponse res, Classify.Classified classified,
            RequestCtx.HttpMethod method, Principal principal) {
        long started = System.nanoTime();
        String requestId = java.util.HexFormat.of().formatHex(randomBytes(8));
        List<Map.Entry<String, String>> headers = new ArrayList<>();
        req.headers().forEach(h -> headers.add(Map.entry(h.name(), h.values())));
        RequestCtx ctx = new RequestCtx(
                method, req.path().rawPath(), classified.endpoint(),
                classified.logicalIndex(), classified.docId(),
                headers, new byte[0], principal, req.query().rawValue());
        try (java.io.InputStream in = hasBody(req)
                ? req.content().inputStream() : java.io.InputStream.nullInputStream()) {
            Pipeline.BulkStream stream;
            try {
                stream = pipeline.openBulkStream(ctx, in);
            } catch (io.osproxy.rewrite.RewriteException e) {
                recordCompletion(requestId, classified, method, principal,
                        ErrorCode.MALFORMED_REQUEST.httpStatus(),
                        Optional.of(ErrorCode.MALFORMED_REQUEST.wireName()),
                        System.nanoTime() - started);
                send(res, PipelineResponse.error(ErrorCode.MALFORMED_REQUEST));
                return;
            }
            res.status(Status.OK_200).header(HeaderNames.create(REQUEST_ID_HEADER), requestId);
            try {
                try (var out = res.outputStream()) {
                    stream.writeTo(out);
                }
            } catch (Exception e) {
                // Once the output stream is requested, the status/headers are
                // already committed — there is no error status left to send,
                // only a best-effort truncation of the response already sent.
                // The client did see a 200, so that's what gets recorded.
                recordCompletion(requestId, classified, method, principal, 200, Optional.empty(),
                        System.nanoTime() - started);
                return;
            }
            recordCompletion(requestId, classified, method, principal, 200, Optional.empty(),
                    System.nanoTime() - started);
        } catch (Exception e) {
            recordCompletion(requestId, classified, method, principal,
                    ErrorCode.UPSTREAM_FAILED.httpStatus(),
                    Optional.of(ErrorCode.UPSTREAM_FAILED.wireName()),
                    System.nanoTime() - started);
            if (!res.isSent()) {
                send(res, PipelineResponse.error(ErrorCode.UPSTREAM_FAILED));
            }
        }
    }

    /** Publish (POST) / introspect (GET) the directive set, token-gated. */
    private void handleAdminDirectives(ServerRequest req, ServerResponse res) {
        // Fail closed: no configured token means the endpoint does not exist.
        if (adminToken.isEmpty()) {
            send(res, PipelineResponse.error(ErrorCode.UNSUPPORTED_ENDPOINT));
            return;
        }
        // A publish mutates fleet-wide observability state: the NFR-S1 gate
        // applies here too, and refusing before reading Authorization keeps
        // the admin token off cleartext entirely.
        if (requireTlsForMutation && !req.isSecure()
                && !req.prologue().method().text().equals("GET")) {
            send(res, PipelineResponse.error(ErrorCode.UNAUTHORIZED));
            return;
        }
        boolean authorized = BearerAuth.matches(
                req.headers().first(HeaderNames.AUTHORIZATION), adminToken.get());
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
        byte[] body = hasBody(req) ? req.content().as(byte[].class) : new byte[0];
        try {
            publishable.publish(api.decode(body));
            res.status(Status.OK_200)
                    .header(HeaderNames.CONTENT_TYPE, "application/json")
                    .send("{\"published\":true}");
        } catch (DirectivesApi.InvalidDirectives e) {
            send(res, PipelineResponse.error(ErrorCode.MALFORMED_REQUEST));
        }
    }

    /**
     * Whether the request actually carries a body worth opening a stream
     * for. {@code content().hasEntity()} alone isn't safe over HTTP/2: a
     * request upgraded from HTTP/1.1 (h2c) with a declared {@code
     * Content-Length: 0} still reports an entity present, and reading from
     * it parks the handling virtual thread forever waiting on a DATA frame
     * that was never going to arrive (confirmed via thread dump against
     * Helidon 4.2.7: {@code Http2ServerStream.readEntityFromPipeline} blocks
     * on an empty queue with no end-of-stream ever delivered for this
     * upgraded-empty-body case). An explicit zero content-length overrides
     * {@code hasEntity()} so no read site ever opens that stream.
     */
    private static boolean hasBody(ServerRequest req) {
        return req.content().hasEntity()
                && req.headers().first(HeaderNames.CONTENT_LENGTH)
                        .map(v -> !v.equals("0"))
                        .orElse(true);
    }

    private static final class OverCapException extends Exception {}

    /** Reads at most the cap; one byte over aborts (never buffers the rest). */
    private byte[] readCapped(java.io.InputStream in)
            throws java.io.IOException, OverCapException {
        var out = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) >= 0) {
            if (out.size() + n > maxBodyBytes) {
                throw new OverCapException();
            }
            out.write(chunk, 0, n);
        }
        return out.toByteArray();
    }

    /** Tallies the completed request, echoes its id, for a streaming path that already has a {@code ServerResponse}. */
    private void record(RequestCtx ctx, ServerResponse res, PipelineResponse out, long durationNanos) {
        res.header(HeaderNames.create(REQUEST_ID_HEADER), recordCompletion(ctx, out, durationNanos));
    }

    /** Tallies the completed request and mints its request id. */
    private String recordCompletion(RequestCtx ctx, PipelineResponse out, long durationNanos) {
        String requestId = java.util.HexFormat.of().formatHex(randomBytes(8));
        Optional<String> errorCode = out.status() >= 400
                ? extractErrorCode(out.body())
                : Optional.empty();
        recordCompletion(requestId, ctx.endpoint(), ctx.method().name(), ctx.logicalIndex(),
                ctx.principal(), out.status(), errorCode, durationNanos);
        return requestId;
    }

    /** Overload for a streaming path that never built a {@link Classify.Classified}-free doc. */
    private void recordCompletion(
            String requestId, Classify.Classified classified, RequestCtx.HttpMethod method,
            Principal principal, int status, Optional<String> errorCode, long durationNanos) {
        recordCompletion(requestId, classified.endpoint(), method.name(),
                classified.logicalIndex(), principal, status, errorCode, durationNanos);
    }

    private void recordCompletion(
            String requestId, io.osproxy.core.EndpointKind endpoint, String methodName,
            Optional<String> logicalIndex, Principal principal, int status,
            Optional<String> errorCode, long durationNanos) {
        var doc = new io.osproxy.observe.ExplainDoc(
                requestId,
                io.osproxy.core.Tracing.CURRENT.get().traceId(),
                endpoint,
                methodName,
                status,
                errorCode,
                durationNanos);
        observability.record(doc, new io.osproxy.observe.Directive.RequestAttrs(
                principal.attribute("tenant").orElse(""),
                logicalIndex,
                endpoint,
                principal.id()));
        if (observability.exporter().enabled()) {
            observability.exporter().export(
                    doc,
                    io.osproxy.core.Tracing.CURRENT.get().spanId(),
                    System.currentTimeMillis() * 1_000_000L);
        }
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

    private static void sendNotEnabled(ServerResponse res) {
        res.status(Status.NOT_FOUND_404)
                .header(HeaderNames.CONTENT_TYPE, "application/json")
                .send("{\"error\":\"not_enabled\"}");
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
