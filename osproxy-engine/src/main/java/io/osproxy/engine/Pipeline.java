package io.osproxy.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.osproxy.core.ErrorCode;
import io.osproxy.rewrite.DocIds;
import io.osproxy.rewrite.Fields;
import io.osproxy.rewrite.Json;
import io.osproxy.rewrite.Queries;
import io.osproxy.rewrite.RewriteException;
import io.osproxy.sink.DocOp;
import io.osproxy.sink.Reader;
import io.osproxy.sink.Sink;
import io.osproxy.sink.SinkException;
import io.osproxy.sink.WriteBatch;
import io.osproxy.spi.DocIdRule;
import io.osproxy.spi.RequestCtx;
import io.osproxy.spi.RouteDecision;
import io.osproxy.spi.SpiException;
import io.osproxy.tenancy.TenancyRouter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The request pipeline: classify → resolve → rewrite → dispatch → shape.
 * One instance serves every request; per-request state lives on the stack of
 * the virtual thread handling it. Anything unclassified or out of scope is
 * refused fail-closed.
 */
public final class Pipeline {

    private final TenancyRouter router;
    private final Sink sink;
    private final Reader reader;
    private final MultiOps multiOps;
    private final Cursors cursors;
    private final Optional<AsyncWrites.AsyncWriteSink> asyncSink;
    private final Optional<PassthroughPolicy> passthrough;
    private boolean deleteByQueryExpansionEnabled = false;
    private Optional<AdminPolicy> adminPolicy = Optional.empty();

    /** The most matches one {@code _delete_by_query} may expand before refusal. */
    private static final long DBQ_MAX_MATCHES = 10_000;

    public Pipeline(TenancyRouter router, Sink sink, Reader reader) {
        this(router, sink, reader, Optional.empty(), Optional.empty());
    }

    /** With a cursor codec, the scroll/PIT endpoints go live. */
    public Pipeline(
            TenancyRouter router, Sink sink, Reader reader, Optional<CursorCodec> cursorCodec) {
        this(router, sink, reader, cursorCodec, Optional.empty());
    }

    /** With an async sink, per-request async write mode goes live too. */
    public Pipeline(
            TenancyRouter router, Sink sink, Reader reader,
            Optional<CursorCodec> cursorCodec,
            Optional<AsyncWrites.AsyncWriteSink> asyncSink) {
        this(router, sink, reader, cursorCodec, asyncSink, Optional.empty());
    }

    /**
     * With a passthrough policy: requests matching it (by logical index) skip
     * tenancy entirely and forward verbatim to the configured cluster.
     */
    public Pipeline(
            TenancyRouter router, Sink sink, Reader reader,
            Optional<CursorCodec> cursorCodec,
            Optional<AsyncWrites.AsyncWriteSink> asyncSink,
            Optional<PassthroughPolicy> passthrough) {
        this.router = router;
        this.sink = sink;
        this.reader = reader;
        this.multiOps = new MultiOps(this);
        this.cursors = new Cursors(this, cursorCodec);
        this.asyncSink = asyncSink;
        this.passthrough = passthrough;
    }

    /**
     * Opts into the {@code _delete_by_query} async expansion (default: off,
     * the endpoint is refused). Only takes effect when async write mode is
     * itself available.
     */
    public Pipeline withDeleteByQueryExpansion(boolean enabled) {
        this.deleteByQueryExpansionEnabled = enabled;
        return this;
    }

    /**
     * Sets the admin pass-through policy: {@code _cat}/{@code _cluster}/
     * {@code _nodes} requests matching an allowed prefix forward verbatim to
     * the configured cluster (default: no policy, admin is always refused).
     */
    public Pipeline withAdminPolicy(AdminPolicy adminPolicy) {
        this.adminPolicy = Optional.of(adminPolicy);
        return this;
    }

    /**
     * The configured passthrough policy, when set — exposed so the ingress
     * (which owns the raw request/response streams) can route a matching
     * request through a true streaming forward instead of this class's own
     * buffered {@link #handle}, without duplicating the match logic.
     */
    public Optional<PassthroughPolicy> passthroughPolicy() {
        return passthrough;
    }

    /**
     * Opens a streaming {@code _bulk} response: reads just enough of
     * {@code in} to confirm the body isn't empty and its first line parses,
     * without dispatching any op yet. That lets the ingress still send a
     * normal error status for those cases, then call {@link
     * BulkStream#writeTo} once it has committed the response as 200 and is
     * ready to stream — after which every item is parsed, dispatched, and
     * written one at a time, never buffering the body as a byte[].
     */
    public BulkStream openBulkStream(RequestCtx ctx, java.io.InputStream in)
            throws RewriteException {
        var reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
        var items = MultiOps.peekBulkStream(reader);
        return new BulkStream(ctx, items);
    }

    /** A validated, not-yet-dispatched streaming {@code _bulk} request. */
    public final class BulkStream {
        private final RequestCtx ctx;
        private final java.util.Iterator<io.osproxy.rewrite.Bulk.Item> items;

        private BulkStream(RequestCtx ctx, java.util.Iterator<io.osproxy.rewrite.Bulk.Item> items) {
            this.ctx = ctx;
            this.items = items;
        }

        /** Dispatches every item, writing each result as it completes. */
        public void writeTo(java.io.OutputStream out)
                throws java.io.IOException, SpiException, RewriteException, SinkException,
                        EngineException {
            var gen = Json.MAPPER.getFactory().createGenerator(out);
            multiOps.bulkStreaming(ctx, items, gen);
        }
    }

    TenancyRouter router() {
        return router;
    }

    Sink sink() {
        return sink;
    }

    /**
     * The reader, exposed so the ingress can drive a streaming passthrough
     * forward directly (see {@link #passthroughPolicy()}).
     */
    public Reader reader() {
        return reader;
    }

    /**
     * Whether {@link #ingestDocStreaming} is usable for this proxy's
     * tenancy: the physical target and id must be derivable without reading
     * the document at all, since streaming means the sink starts uploading
     * before the whole body has arrived. That rules out a body-derived
     * partition key ({@link io.osproxy.spi.PartitionKeySpec.BodyField}).
     * {@code DocIdRule} always requires an {@code {id}} placeholder (its
     * compact constructor enforces it), so the doc-id side is never actually
     * body-derived in practice — the check is kept anyway as the one thing
     * that would make streaming genuinely impossible if that constraint ever
     * loosened. Every other configuration (including the reference tenancy)
     * qualifies.
     */
    public boolean supportsStreamingIngest() {
        if (needsBody(router.spi().partitionKeySpec())) {
            return false;
        }
        return router.spi().docIdRule()
                .map(rule -> rule.template().contains("{id}"))
                .orElse(true);
    }

    private static boolean needsBody(io.osproxy.spi.PartitionKeySpec spec) {
        return switch (spec) {
            case io.osproxy.spi.PartitionKeySpec.BodyField ignored -> true;
            case io.osproxy.spi.PartitionKeySpec.AnyOf(var sources) ->
                    sources.stream().anyMatch(Pipeline::needsBody);
            case io.osproxy.spi.PartitionKeySpec.Header ignored -> false;
            case io.osproxy.spi.PartitionKeySpec.PrincipalAttr ignored -> false;
        };
    }

    /**
     * Streaming twin of {@link #ingestDoc}, usable only when {@link
     * #supportsStreamingIngest()}: routes and resolves the injected fields
     * from {@code ctx} alone (never the body), then pipes a token-level
     * field-injection transform of {@code requestBody} straight into the
     * upstream request. A background virtual thread runs the transform
     * (parse + inject + write) into one end of a pipe while the sink reads
     * the other end as it uploads — the document is never buffered whole at
     * either end. The response itself is the small, already-buffered ack
     * every ingest returns; only the (potentially large) request body
     * streams.
     */
    public PipelineResponse ingestDocStreaming(RequestCtx ctx, java.io.InputStream requestBody)
            throws SpiException, RewriteException, SinkException {
        RouteDecision decision = router.route(ctx, null);
        if (!router.spi().admitWrite(decision.partition(), decision.epoch())) {
            return PipelineResponse.error(ErrorCode.STALE_EPOCH);
        }
        Map<String, JsonNode> inject = Transforms.resolveInjected(
                Transforms.injectedFields(decision.transform()), decision.partition(), ctx);
        Optional<DocIdRule> rule = Transforms.idRule(decision.transform());
        String logicalId = ctx.docId().orElseGet(() -> UUID.randomUUID().toString());
        String physicalId = mapId(rule, decision, logicalId);
        Optional<String> routing = routing(rule, decision);
        boolean create = ctx.path().contains("/_create/");

        var pipedOut = new java.io.PipedOutputStream();
        java.io.PipedInputStream pipedIn;
        try {
            pipedIn = new java.io.PipedInputStream(pipedOut, 8192);
        } catch (java.io.IOException e) {
            return PipelineResponse.error(ErrorCode.MALFORMED_REQUEST);
        }
        var failure = new java.util.concurrent.atomic.AtomicReference<Exception>();
        Thread producer = Thread.ofVirtual().start(() -> {
            try (pipedOut) {
                var parser = Json.MAPPER.getFactory().createParser(requestBody);
                var generator = Json.MAPPER.getFactory().createGenerator(pipedOut);
                Fields.injectFieldsStreaming(parser, generator, inject);
                generator.close();
            } catch (Exception e) {
                failure.set(e);
            }
        });

        // The producer failing (cap exceeded, malformed json) races with the
        // sink reading whatever partial bytes made it through before the
        // pipe closed — that read can "succeed" against a truncated document
        // and report its own (misleading) failure. The producer's failure is
        // the real one and takes priority.
        WriteBatch.OpResult result = null;
        SinkException sinkFailure = null;
        try {
            result = sink.writeStreaming(decision.target(), create, physicalId, pipedIn, routing);
        } catch (SinkException e) {
            sinkFailure = e;
        } finally {
            try {
                producer.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (failure.get() != null) {
            if (hasCause(failure.get(), io.osproxy.core.BodyTooLargeException.class)) {
                return PipelineResponse.error(ErrorCode.PAYLOAD_TOO_LARGE);
            }
            throw new RewriteException(RewriteException.Kind.INVALID_JSON, "malformed ingest body");
        }
        if (sinkFailure != null) {
            throw sinkFailure;
        }
        return ackResponse(ctx, result, logicalId);
    }

    private static boolean hasCause(Throwable t, Class<? extends Throwable> type) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (type.isInstance(cur)) {
                return true;
            }
        }
        return false;
    }

    /** Handles one classified, authenticated request. */
    public PipelineResponse handle(RequestCtx ctx) {
        try {
            // Tenant-agnostic passthrough short-circuits dispatch for the
            // requests it matches (by logical index); unmatched requests fall
            // through to tenancy below (fail-closed).
            if (passthrough.isPresent() && passthrough.get().matches(ctx)) {
                return forward(ctx, passthrough.get());
            }
            if (AsyncWrites.wantsAsync(ctx)) {
                // Refuse-don't-lie: async mode exists only for single-doc
                // writes and delete-by-query, and only when a durable sink is
                // wired. Delete-by-query has its own checks below (different
                // response shapes matching its own refuse-don't-lie contract),
                // so it is exempted from this generic gate.
                if (ctx.endpoint() != io.osproxy.core.EndpointKind.INGEST_DOC
                        && ctx.endpoint() != io.osproxy.core.EndpointKind.DELETE_BY_ID
                        && ctx.endpoint() != io.osproxy.core.EndpointKind.DELETE_BY_QUERY) {
                    return PipelineResponse.error(ErrorCode.UNSUPPORTED_ENDPOINT);
                }
                if (ctx.endpoint() != io.osproxy.core.EndpointKind.DELETE_BY_QUERY
                        && asyncSink.isEmpty()) {
                    return PipelineResponse.error(ErrorCode.UPSTREAM_UNAVAILABLE);
                }
            }
            return switch (ctx.endpoint()) {
                case INGEST_DOC -> ingestDoc(ctx);
                case GET_BY_ID -> getById(ctx);
                case DELETE_BY_ID -> deleteById(ctx);
                case DELETE_BY_QUERY -> deleteByQuery(ctx);
                case SEARCH -> searchOrCount(ctx, true);
                case COUNT -> searchOrCount(ctx, false);
                case INGEST_BULK -> multiOps.bulk(ctx);
                case MULTI_GET -> multiOps.mget(ctx);
                case MULTI_SEARCH -> multiOps.msearch(ctx);
                case CURSOR -> cursors.handle(ctx);
                case ADMIN -> admin(ctx);
                case UNKNOWN -> PipelineResponse.error(ErrorCode.UNSUPPORTED_ENDPOINT);
            };
        } catch (SpiException e) {
            return PipelineResponse.error(e.errorCode());
        } catch (RewriteException e) {
            return PipelineResponse.error(ErrorCode.MALFORMED_REQUEST);
        } catch (SinkException e) {
            return PipelineResponse.error(e.errorCode());
        } catch (EngineException e) {
            return PipelineResponse.error(e.errorCode());
        }
    }

    /**
     * Forwards {@code ctx} verbatim to the passthrough cluster and returns
     * the raw upstream response, unshaped. Reuses the reader's generic
     * verbatim-forward primitive; the client's forwarded header set (bound
     * per-request by the ingress) reaches the upstream through the sink's
     * own choke point (the same one every other endpoint's call goes
     * through), not passed explicitly here, so passthrough carries client
     * headers with the same policy as every other endpoint.
     */
    private PipelineResponse forward(RequestCtx ctx, PassthroughPolicy policy)
            throws SinkException {
        Reader.Response outcome = reader.forward(
                policy.target(), ctx.method(), ctx.path(), ctx.rawQuery(), ctx.body(), List.of());
        return new PipelineResponse(outcome.status(), outcome.body());
    }

    /**
     * Forwards an admin ({@code _cat}/{@code _cluster}/{@code _nodes})
     * request verbatim to the operator-configured admin cluster, when the
     * path matches an allowed prefix. Admin output is cluster-wide, not
     * tenant-scoped — the allow-list is the only safety boundary, so a
     * request that doesn't match one is refused exactly like an unsupported
     * endpoint, not silently narrowed.
     */
    private PipelineResponse admin(RequestCtx ctx) throws SinkException {
        if (adminPolicy.isEmpty() || !adminPolicy.get().allows(ctx.path())) {
            return PipelineResponse.error(ErrorCode.UNSUPPORTED_ENDPOINT);
        }
        AdminPolicy policy = adminPolicy.get();
        io.osproxy.core.Target target = new io.osproxy.core.Target(
                policy.cluster(), new io.osproxy.core.IndexName("admin"), policy.endpoint());
        Reader.Response outcome = reader.forward(
                target, ctx.method(), ctx.path(), ctx.rawQuery(), ctx.body(), List.of());
        return new PipelineResponse(outcome.status(), outcome.body());
    }

    /**
     * Runs the {@code _delete_by_query} async expansion: delete-by-query has
     * no synchronous implementation (a query-driven mutation the fan-out
     * queue cannot carry as a single op), so in async mode, with expansion
     * opted in, the proxy runs the partition-scoped match query itself (the
     * same mandatory isolation filter as a normal search), caps the match
     * set, and enqueues a concrete delete per matched physical id — refusing
     * anything else (sync mode, expansion disabled, no queue, over-cap match
     * set) with a shape matching the rest of the refuse-don't-lie contract.
     */
    private PipelineResponse deleteByQuery(RequestCtx ctx)
            throws SpiException, RewriteException, SinkException {
        String index = ctx.logicalIndex().orElse("");
        if (!AsyncWrites.wantsAsync(ctx)) {
            return dbqUnsupported("delete_by_query is only supported in async write mode", index);
        }
        if (!deleteByQueryExpansionEnabled) {
            return dbqUnsupported("delete_by_query expansion is not enabled on this proxy", index);
        }
        if (asyncSink.isEmpty()) {
            return dbqUnavailable(index);
        }

        RouteDecision decision = router.route(ctx, null);
        Map<String, JsonNode> filter = Transforms.resolveInjected(
                Transforms.injectedFields(decision.transform()), decision.partition(), ctx);
        byte[] wrapped = Queries.wrapQuery(ctx.body(), filter);
        byte[] capped = capIdsOnly(wrapped);

        Reader.Response upstream = reader.search(decision.target(), capped);
        if (!upstream.ok()) {
            return PipelineResponse.error(ErrorCode.UPSTREAM_FAILED);
        }
        JsonNode doc;
        try {
            doc = Json.MAPPER.readTree(upstream.body());
        } catch (java.io.IOException e) {
            throw new RewriteException(
                    RewriteException.Kind.INVALID_JSON,
                    "delete_by_query match response was not valid json");
        }
        long total = doc.path("hits").path("total").path("value").asLong(0);
        if (total > DBQ_MAX_MATCHES) {
            return dbqUnsupported("delete_by_query match set exceeds the proxy cap", index);
        }

        Optional<String> routing = routing(Transforms.idRule(decision.transform()), decision);
        long deleted = 0;
        var failures = Json.MAPPER.createArrayNode();
        for (JsonNode hit : doc.path("hits").path("hits")) {
            JsonNode idNode = hit.get("_id");
            if (idNode == null) {
                continue;
            }
            WriteBatch.Op writeOp = new WriteBatch.Op(
                    decision.target(),
                    new DocOp.Delete(idNode.asText(), routing),
                    decision.epoch());
            PipelineResponse enqueued = AsyncWrites.enqueue(asyncSink.get(), writeOp);
            if (enqueued.status() == 202) {
                deleted++;
            } else {
                failures.add(Json.MAPPER.createObjectNode()
                        .put("status", 503)
                        .put("type", "enqueue_failed"));
            }
        }

        ObjectNode out = Json.MAPPER.createObjectNode();
        out.put("took", 0);
        out.put("timed_out", false);
        out.put("total", total);
        out.put("deleted", deleted);
        out.put("version_conflicts", 0);
        out.put("batches", 1);
        out.set("failures", failures);
        return new PipelineResponse(200, Json.writeBytes(out));
    }

    /** Caps a partition-filtered search body to ids-only, with an accurate total. */
    private static byte[] capIdsOnly(byte[] wrappedSearchBody) throws RewriteException {
        ObjectNode doc = Json.parseObject(wrappedSearchBody);
        doc.put("size", DBQ_MAX_MATCHES + 1);
        doc.put("_source", false);
        doc.put("track_total_hits", true);
        return Json.writeBytes(doc);
    }

    private static PipelineResponse dbqUnsupported(String reason, String index) {
        ObjectNode out = Json.MAPPER.createObjectNode();
        out.put("status", "rejected");
        out.put("error", reason);
        out.put("_index", index);
        return new PipelineResponse(400, Json.writeBytes(out));
    }

    private static PipelineResponse dbqUnavailable(String index) {
        ObjectNode out = Json.MAPPER.createObjectNode();
        out.put("status", "rejected");
        out.put("error", "async write mode is not available on this proxy");
        out.put("_index", index);
        return new PipelineResponse(422, Json.writeBytes(out));
    }

    private PipelineResponse ingestDoc(RequestCtx ctx)
            throws SpiException, RewriteException, SinkException {
        ObjectNode doc = Json.parseObject(ctx.body());
        RouteDecision decision = router.route(ctx, doc);
        if (!router.spi().admitWrite(decision.partition(), decision.epoch())) {
            return PipelineResponse.error(ErrorCode.STALE_EPOCH);
        }

        Map<String, JsonNode> inject = Transforms.resolveInjected(
                Transforms.injectedFields(decision.transform()), decision.partition(), ctx);
        Fields.injectFields(doc, inject);

        Optional<DocIdRule> rule = Transforms.idRule(decision.transform());
        String logicalId = ctx.docId().orElseGet(() -> UUID.randomUUID().toString());
        String physicalId = physicalId(rule, decision, logicalId, doc);
        Optional<String> routing = routing(rule, decision);

        boolean create = ctx.path().contains("/_create/");
        byte[] body = Json.writeBytes(doc);
        DocOp op = create
                ? new DocOp.Create(physicalId, body, routing)
                : new DocOp.Index(physicalId, body, routing);
        WriteBatch.Op writeOp = new WriteBatch.Op(decision.target(), op, decision.epoch());
        if (AsyncWrites.wantsAsync(ctx)) {
            return AsyncWrites.enqueue(asyncSink.orElseThrow(), writeOp);
        }
        WriteBatch.Ack ack = sink.write(List.of(writeOp));

        WriteBatch.OpResult result = ack.results().get(0);
        return ackResponse(ctx, result, logicalId);
    }

    private PipelineResponse getById(RequestCtx ctx)
            throws SpiException, RewriteException, SinkException {
        RouteDecision decision = router.route(ctx, null);
        Optional<DocIdRule> rule = Transforms.idRule(decision.transform());
        String physicalId = mapId(rule, decision, ctx.docId().orElseThrow());

        Reader.Response upstream = reader.get(
                decision.target(), physicalId, routing(rule, decision));
        if (upstream.status() == 404) {
            return notFound(ctx, ctx.docId().orElseThrow());
        }
        if (!upstream.ok()) {
            // Upstream trouble is upstream's fault, never reshaped into a 400.
            return PipelineResponse.error(ErrorCode.UPSTREAM_FAILED);
        }
        byte[] shaped = Shaping.shape(upstream.body(), view(ctx, decision), false);
        return new PipelineResponse(upstream.status(), shaped);
    }

    private PipelineResponse deleteById(RequestCtx ctx)
            throws SpiException, RewriteException, SinkException {
        RouteDecision decision = router.route(ctx, null);
        if (!router.spi().admitWrite(decision.partition(), decision.epoch())) {
            return PipelineResponse.error(ErrorCode.STALE_EPOCH);
        }
        Optional<DocIdRule> rule = Transforms.idRule(decision.transform());
        String logicalId = ctx.docId().orElseThrow();
        String physicalId = mapId(rule, decision, logicalId);

        WriteBatch.Op writeOp = new WriteBatch.Op(
                decision.target(),
                new DocOp.Delete(physicalId, routing(rule, decision)),
                decision.epoch());
        if (AsyncWrites.wantsAsync(ctx)) {
            return AsyncWrites.enqueue(asyncSink.orElseThrow(), writeOp);
        }
        WriteBatch.Ack ack = sink.write(List.of(writeOp));
        return ackResponse(ctx, ack.results().get(0), logicalId);
    }

    private PipelineResponse searchOrCount(RequestCtx ctx, boolean search)
            throws SpiException, RewriteException, SinkException {
        if (search) {
            // A ?scroll= search opens a cursor; a body naming a pit id is a
            // PIT search. Both hand off to the affinity-sealing cursor path.
            Optional<String> ttl = Cursors.scrollTtl(ctx);
            if (ttl.isPresent()) {
                return cursors.openScroll(ctx, ttl.get());
            }
            if (ctx.logicalIndex().isEmpty() && bodyNamesAPit(ctx.body())) {
                return cursors.pitSearch(ctx);
            }
        }
        RouteDecision decision = router.route(ctx, null);
        Map<String, JsonNode> filter = Transforms.resolveInjected(
                Transforms.injectedFields(decision.transform()), decision.partition(), ctx);
        byte[] wrapped = Queries.wrapQuery(ctx.body(), filter);

        Reader.Response upstream = search
                ? reader.search(decision.target(), wrapped)
                : reader.count(decision.target(), wrapped);
        if (!upstream.ok()) {
            return PipelineResponse.error(ErrorCode.UPSTREAM_FAILED);
        }
        byte[] body = search
                ? Shaping.shape(upstream.body(), view(ctx, decision), true)
                : upstream.body();
        return new PipelineResponse(upstream.status(), body);
    }

    /**
     * Streaming twin of {@link #searchOrCount}, usable only for the ordinary
     * index-present case: the ingress checks {@code ctx.queryParam("scroll")}
     * itself before offering this path (scroll-open and PIT search both need
     * the buffered body for their own reasons, so they stay on the buffered
     * path — see {@code AppHandler}). Streams {@code
     * Queries.wrapQueryStreaming}'s token-level transform straight into the
     * upstream request the same way {@link #ingestDocStreaming} streams
     * field injection: a background virtual thread runs the transform into
     * one end of a pipe while the sink reads the other end as it uploads.
     * The response is still the ordinary buffered one (shaping needs the
     * whole tree), so only the (potentially large) request body streams.
     */
    public PipelineResponse searchStreaming(
            RequestCtx ctx, java.io.InputStream requestBody, boolean search)
            throws SpiException, RewriteException, SinkException {
        RouteDecision decision = router.route(ctx, null);
        Map<String, JsonNode> filter = Transforms.resolveInjected(
                Transforms.injectedFields(decision.transform()), decision.partition(), ctx);

        // A dedicated placement has nothing to wrap or screen (the buffered
        // path's own short-circuit) — pass the body straight through with no
        // transform at all, not even the pipe/producer-thread machinery.
        if (filter.isEmpty()) {
            Reader.Response verbatim;
            try {
                verbatim = search
                        ? reader.searchStreaming(decision.target(), requestBody)
                        : reader.countStreaming(decision.target(), requestBody);
            } catch (SinkException e) {
                // The cap-exceeding CappingInputStream throws from inside
                // Helidon's outputStream() write, several wrapper layers
                // below this SinkException — still worth surfacing as 413
                // rather than a generic upstream failure.
                if (hasCause(e, io.osproxy.core.BodyTooLargeException.class)) {
                    return PipelineResponse.error(ErrorCode.PAYLOAD_TOO_LARGE);
                }
                throw e;
            }
            if (!verbatim.ok()) {
                return PipelineResponse.error(ErrorCode.UPSTREAM_FAILED);
            }
            byte[] verbatimBody = search
                    ? Shaping.shape(verbatim.body(), view(ctx, decision), true)
                    : verbatim.body();
            return new PipelineResponse(verbatim.status(), verbatimBody);
        }

        var pipedOut = new java.io.PipedOutputStream();
        java.io.PipedInputStream pipedIn;
        try {
            pipedIn = new java.io.PipedInputStream(pipedOut, 8192);
        } catch (java.io.IOException e) {
            return PipelineResponse.error(ErrorCode.MALFORMED_REQUEST);
        }
        var failure = new java.util.concurrent.atomic.AtomicReference<Exception>();
        Thread producer = Thread.ofVirtual().start(() -> {
            try (pipedOut) {
                var parser = Json.MAPPER.getFactory().createParser(requestBody);
                var generator = Json.MAPPER.getFactory().createGenerator(pipedOut);
                Queries.wrapQueryStreaming(parser, generator, filter);
                generator.close();
            } catch (Exception e) {
                failure.set(e);
            }
        });

        // Same priority as ingestDocStreaming: the producer's failure (cap
        // exceeded, unfilterable construct, malformed json) races the sink
        // reading whatever partial bytes crossed before the pipe closed, and
        // takes priority over that read's own (misleading) outcome.
        Reader.Response upstream = null;
        SinkException sinkFailure = null;
        try {
            upstream = search
                    ? reader.searchStreaming(decision.target(), pipedIn)
                    : reader.countStreaming(decision.target(), pipedIn);
        } catch (SinkException e) {
            sinkFailure = e;
        } finally {
            try {
                producer.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (failure.get() != null) {
            if (failure.get() instanceof RewriteException re) {
                throw re;
            }
            if (hasCause(failure.get(), io.osproxy.core.BodyTooLargeException.class)) {
                return PipelineResponse.error(ErrorCode.PAYLOAD_TOO_LARGE);
            }
            throw new RewriteException(RewriteException.Kind.INVALID_JSON, "malformed search body");
        }
        if (sinkFailure != null) {
            throw sinkFailure;
        }
        if (!upstream.ok()) {
            return PipelineResponse.error(ErrorCode.UPSTREAM_FAILED);
        }
        byte[] body = search
                ? Shaping.shape(upstream.body(), view(ctx, decision), true)
                : upstream.body();
        return new PipelineResponse(upstream.status(), body);
    }

    /** Whether an index-less search body carries a {@code pit} clause. */
    private static boolean bodyNamesAPit(byte[] body) {
        if (body.length == 0) {
            return false;
        }
        try {
            return Json.parseObject(body).has("pit");
        } catch (RewriteException e) {
            return false; // the normal search path reports the malformed body
        }
    }

    // ---- shared helpers (also used by MultiOps) ----

    Shaping.View view(RequestCtx ctx, RouteDecision decision) {
        return new Shaping.View(
                ctx.logicalIndex().orElse(decision.target().index().value()),
                decision.partition().value(),
                Transforms.idRule(decision.transform()),
                Transforms.injectedFields(decision.transform()).stream()
                        .map(io.osproxy.spi.InjectedField::name)
                        .toList());
    }

    String physicalId(
            Optional<DocIdRule> rule, RouteDecision decision, String logicalId, ObjectNode doc)
            throws RewriteException {
        if (rule.isEmpty()) {
            return logicalId;
        }
        // A template with {id} frames the client id; a {body.*} template
        // derives the id from the document.
        String template = rule.get().template();
        return template.contains("{id}")
                ? DocIds.mapLogicalToPhysical(template, decision.partition().value(), logicalId)
                : DocIds.constructId(template, decision.partition().value(), doc);
    }

    String mapId(Optional<DocIdRule> rule, RouteDecision decision, String logicalId)
            throws RewriteException {
        return rule.isPresent()
                ? DocIds.mapLogicalToPhysical(
                        rule.get().template(), decision.partition().value(), logicalId)
                : logicalId;
    }

    Optional<String> routing(Optional<DocIdRule> rule, RouteDecision decision) {
        return rule.filter(DocIdRule::setRouting).map(r -> decision.partition().value());
    }

    /** An OpenSearch-shaped single-doc ack with logical labels. */
    private PipelineResponse ackResponse(
            RequestCtx ctx, WriteBatch.OpResult result, String logicalId) {
        ObjectNode out = Json.MAPPER.createObjectNode();
        out.put("_index", ctx.logicalIndex().orElse(""));
        out.put("_id", logicalId);
        out.put("result", result.result());
        return new PipelineResponse(result.status(), Json.writeBytes(out));
    }

    private PipelineResponse notFound(RequestCtx ctx, String logicalId) {
        ObjectNode out = Json.MAPPER.createObjectNode();
        out.put("_index", ctx.logicalIndex().orElse(""));
        out.put("_id", logicalId);
        out.put("found", false);
        return new PipelineResponse(404, Json.writeBytes(out));
    }
}
