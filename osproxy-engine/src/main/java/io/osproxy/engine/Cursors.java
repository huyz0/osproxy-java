package io.osproxy.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.osproxy.core.ClusterId;
import io.osproxy.core.ErrorCode;
import io.osproxy.core.IndexName;
import io.osproxy.core.Target;
import io.osproxy.rewrite.Json;
import io.osproxy.rewrite.Queries;
import io.osproxy.rewrite.RewriteException;
import io.osproxy.sink.Reader;
import io.osproxy.sink.SinkException;
import io.osproxy.spi.RequestCtx;
import io.osproxy.spi.RouteDecision;
import io.osproxy.spi.SpiException;
import java.util.Map;
import java.util.Optional;

/**
 * The cursor endpoints (scroll + PIT). Every cursor id crossing the client
 * boundary is wrapped by the {@link CursorCodec} with its cluster affinity,
 * so a continue routes to the cluster that owns the cursor even if the
 * partition's placement changes mid-scroll. No codec configured means every
 * cursor request is refused fail-closed — silent mis-affinity is a
 * cross-cluster data hazard, not a degraded mode.
 */
final class Cursors {

    private final Pipeline pipeline;
    private final Optional<CursorCodec> codec;

    Cursors(Pipeline pipeline, Optional<CursorCodec> codec) {
        this.pipeline = pipeline;
        this.codec = codec;
    }

    /** Whether a search request is actually a scroll open ({@code ?scroll=}). */
    static Optional<String> scrollTtl(RequestCtx ctx) {
        return ctx.queryParam("scroll").filter(ttl -> !ttl.isEmpty());
    }

    /** A scroll open: a routed, wrapped search whose response carries a cursor. */
    PipelineResponse openScroll(RequestCtx ctx, String ttl)
            throws SpiException, RewriteException, SinkException {
        CursorCodec codec = requireCodec();
        RouteDecision decision = pipeline.router().route(ctx, null);
        Map<String, JsonNode> filter = Transforms.resolveInjected(
                Transforms.injectedFields(decision.transform()), decision.partition(), ctx);
        byte[] wrapped = Queries.wrapQuery(ctx.body(), filter);

        Reader.Response upstream = pipeline.reader().searchScroll(decision.target(), wrapped, ttl);
        if (!upstream.ok()) {
            return PipelineResponse.error(ErrorCode.UPSTREAM_FAILED);
        }
        ObjectNode doc = Json.parseObject(upstream.body());
        Shaping.shapeSearchHits(doc, pipeline.view(ctx, decision));
        sealCursor(doc, "_scroll_id", codec, decision.target().cluster().value());
        return new PipelineResponse(upstream.status(), Json.writeBytes(doc));
    }

    /** A PIT search: index-less {@code _search} whose body names a pit id. */
    PipelineResponse pitSearch(RequestCtx ctx)
            throws SpiException, RewriteException, SinkException {
        CursorCodec codec = requireCodec();
        ObjectNode body = Json.parseObject(ctx.body());
        JsonNode pit = body.get("pit");
        if (!(pit instanceof ObjectNode pitNode) || !pitNode.has("id")) {
            return PipelineResponse.error(ErrorCode.MALFORMED_REQUEST);
        }
        CursorCodec.Decoded decoded = decode(codec, pitNode.get("id").asText());
        pitNode.put("id", decoded.upstreamId());

        RouteDecision decision = pipeline.router().routeCursor(ctx);
        Map<String, JsonNode> filter = Transforms.resolveInjected(
                Transforms.injectedFields(decision.transform()), decision.partition(), ctx);
        byte[] wrapped = Queries.wrapQuery(Json.writeBytes(body), filter);

        Reader.Response upstream = pipeline.reader().searchIndexless(
                affinityTarget(decision, decoded.cluster()), wrapped);
        if (!upstream.ok()) {
            return PipelineResponse.error(ErrorCode.UPSTREAM_FAILED);
        }
        ObjectNode doc = Json.parseObject(upstream.body());
        Shaping.shapeSearchHits(doc, pipeline.view(ctx, decision));
        sealCursor(doc, "pit_id", codec, decoded.cluster());
        return new PipelineResponse(upstream.status(), Json.writeBytes(doc));
    }

    /** Dispatches the CURSOR endpoint (scroll continue/close, PIT open/close). */
    PipelineResponse handle(RequestCtx ctx)
            throws SpiException, RewriteException, SinkException {
        CursorCodec codec = requireCodec();
        boolean pit = ctx.path().endsWith("/point_in_time");
        return switch (ctx.method()) {
            case GET, POST -> pit ? pitOpen(ctx, codec) : scrollNext(ctx, codec);
            case DELETE -> pit ? pitClose(ctx, codec) : scrollClose(ctx, codec);
            default -> PipelineResponse.error(ErrorCode.UNSUPPORTED_ENDPOINT);
        };
    }

    private PipelineResponse pitOpen(RequestCtx ctx, CursorCodec codec)
            throws SpiException, RewriteException, SinkException {
        // A PIT opens on an index: /{index}/_search/point_in_time?keep_alive=.
        if (ctx.logicalIndex().isEmpty()) {
            return PipelineResponse.error(ErrorCode.MALFORMED_REQUEST);
        }
        String keepAlive = ctx.queryParam("keep_alive")
                .filter(ka -> !ka.isEmpty())
                .orElse("1m");
        RouteDecision decision = pipeline.router().route(ctx, null);
        Reader.Response upstream = pipeline.reader().pitOpen(decision.target(), keepAlive);
        if (!upstream.ok()) {
            return PipelineResponse.error(ErrorCode.UPSTREAM_FAILED);
        }
        ObjectNode doc = Json.parseObject(upstream.body());
        sealCursor(doc, "pit_id", codec, decision.target().cluster().value());
        return new PipelineResponse(upstream.status(), Json.writeBytes(doc));
    }

    private PipelineResponse pitClose(RequestCtx ctx, CursorCodec codec)
            throws SpiException, RewriteException, SinkException {
        ObjectNode body = Json.parseObject(ctx.body());
        JsonNode ids = body.get("pit_id");
        String cluster = null;
        if (ids instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                CursorCodec.Decoded decoded = decode(codec, array.get(i).asText());
                cluster = decoded.cluster();
                array.set(i, Json.MAPPER.getNodeFactory().textNode(decoded.upstreamId()));
            }
        } else if (ids != null && ids.isTextual()) {
            CursorCodec.Decoded decoded = decode(codec, ids.textValue());
            cluster = decoded.cluster();
            body.put("pit_id", decoded.upstreamId());
        }
        if (cluster == null) {
            return PipelineResponse.error(ErrorCode.MALFORMED_REQUEST);
        }
        RouteDecision decision = pipeline.router().routeCursor(ctx);
        Reader.Response upstream = pipeline.reader().pitClose(
                affinityTarget(decision, cluster), Json.writeBytes(body));
        return new PipelineResponse(upstream.status(), upstream.body());
    }

    private PipelineResponse scrollNext(RequestCtx ctx, CursorCodec codec)
            throws SpiException, RewriteException, SinkException {
        ObjectNode body = Json.parseObject(ctx.body());
        JsonNode id = body.get("scroll_id");
        if (id == null || !id.isTextual()) {
            return PipelineResponse.error(ErrorCode.MALFORMED_REQUEST);
        }
        CursorCodec.Decoded decoded = decode(codec, id.textValue());
        body.put("scroll_id", decoded.upstreamId());

        RouteDecision decision = pipeline.router().routeCursor(ctx);
        Reader.Response upstream = pipeline.reader().scrollNext(
                affinityTarget(decision, decoded.cluster()), Json.writeBytes(body));
        if (!upstream.ok()) {
            return PipelineResponse.error(ErrorCode.UPSTREAM_FAILED);
        }
        ObjectNode doc = Json.parseObject(upstream.body());
        Shaping.shapeSearchHits(doc, pipeline.view(ctx, decision));
        sealCursor(doc, "_scroll_id", codec, decoded.cluster());
        return new PipelineResponse(upstream.status(), Json.writeBytes(doc));
    }

    private PipelineResponse scrollClose(RequestCtx ctx, CursorCodec codec)
            throws SpiException, RewriteException, SinkException {
        ObjectNode body = Json.parseObject(ctx.body());
        JsonNode id = body.get("scroll_id");
        if (id == null || !id.isTextual()) {
            return PipelineResponse.error(ErrorCode.MALFORMED_REQUEST);
        }
        CursorCodec.Decoded decoded = decode(codec, id.textValue());
        body.put("scroll_id", decoded.upstreamId());
        RouteDecision decision = pipeline.router().routeCursor(ctx);
        Reader.Response upstream = pipeline.reader().scrollDelete(
                affinityTarget(decision, decoded.cluster()), Json.writeBytes(body));
        return new PipelineResponse(upstream.status(), upstream.body());
    }

    /** The routed target, re-pinned to the cursor's sealed cluster affinity. */
    private static Target affinityTarget(RouteDecision decision, String cluster) {
        return new Target(
                new ClusterId(cluster),
                new IndexName("cursor"),
                decision.target().cluster().value().equals(cluster)
                        ? decision.target().endpointOverride()
                        : Optional.empty());
    }

    /** Replaces an upstream cursor id in {@code doc} with its sealed form. */
    private static void sealCursor(
            ObjectNode doc, String field, CursorCodec codec, String cluster) {
        JsonNode id = doc.get(field);
        if (id != null && id.isTextual()) {
            doc.put(field, codec.encode(cluster, id.textValue()));
        }
    }

    private CursorCodec requireCodec() throws SpiException {
        return codec.orElseThrow(() ->
                new SpiException.UnsupportedEndpoint(io.osproxy.core.EndpointKind.CURSOR));
    }

    /** Decodes or refuses: a forged/invalid envelope is a malformed request. */
    private static CursorCodec.Decoded decode(CursorCodec codec, String wireId)
            throws RewriteException {
        return codec.decode(wireId).orElseThrow(() -> new RewriteException(
                RewriteException.Kind.INVALID_JSON, "invalid cursor envelope"));
    }
}
