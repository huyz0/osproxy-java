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

    public Pipeline(TenancyRouter router, Sink sink, Reader reader) {
        this(router, sink, reader, Optional.empty());
    }

    /** With a cursor codec, the scroll/PIT endpoints go live. */
    public Pipeline(
            TenancyRouter router, Sink sink, Reader reader, Optional<CursorCodec> cursorCodec) {
        this.router = router;
        this.sink = sink;
        this.reader = reader;
        this.multiOps = new MultiOps(this);
        this.cursors = new Cursors(this, cursorCodec);
    }

    TenancyRouter router() {
        return router;
    }

    Sink sink() {
        return sink;
    }

    Reader reader() {
        return reader;
    }

    /** Handles one classified, authenticated request. */
    public PipelineResponse handle(RequestCtx ctx) {
        try {
            return switch (ctx.endpoint()) {
                case INGEST_DOC -> ingestDoc(ctx);
                case GET_BY_ID -> getById(ctx);
                case DELETE_BY_ID -> deleteById(ctx);
                case SEARCH -> searchOrCount(ctx, true);
                case COUNT -> searchOrCount(ctx, false);
                case INGEST_BULK -> multiOps.bulk(ctx);
                case MULTI_GET -> multiOps.mget(ctx);
                case MULTI_SEARCH -> multiOps.msearch(ctx);
                case CURSOR -> cursors.handle(ctx);
                case ADMIN, UNKNOWN ->
                        PipelineResponse.error(ErrorCode.UNSUPPORTED_ENDPOINT);
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
        WriteBatch.Ack ack = sink.write(List.of(
                new WriteBatch.Op(decision.target(), op, decision.epoch())));

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

        WriteBatch.Ack ack = sink.write(List.of(new WriteBatch.Op(
                decision.target(),
                new DocOp.Delete(physicalId, routing(rule, decision)),
                decision.epoch())));
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
