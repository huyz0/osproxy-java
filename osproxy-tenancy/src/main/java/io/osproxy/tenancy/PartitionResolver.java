package io.osproxy.tenancy;

import com.fasterxml.jackson.databind.JsonNode;
import io.osproxy.core.PartitionId;
import io.osproxy.rewrite.Json;
import io.osproxy.rewrite.RewriteException;
import io.osproxy.spi.PartitionKeySpec;
import io.osproxy.spi.RequestCtx;
import io.osproxy.spi.SpiException;
import java.util.Optional;

/**
 * Resolves a request's partition key from a {@link PartitionKeySpec}: body
 * field, header, principal attribute, or the first of several. The body is
 * passed pre-parsed (possibly null for bodyless requests) so bulk demux can
 * resolve per-document without re-parsing.
 */
public final class PartitionResolver {

    private PartitionResolver() {}

    /** Resolves per the spec, or throws {@link SpiException.PartitionUnresolved}. */
    public static PartitionId resolve(PartitionKeySpec spec, RequestCtx ctx, JsonNode body)
            throws SpiException {
        return tryResolve(spec, ctx, body)
                .orElseThrow(SpiException.PartitionUnresolved::new);
    }

    private static Optional<PartitionId> tryResolve(
            PartitionKeySpec spec, RequestCtx ctx, JsonNode body) {
        return switch (spec) {
            case PartitionKeySpec.Header(String name) ->
                    ctx.header(name).map(PartitionId::new);
            case PartitionKeySpec.PrincipalAttr(String attr) ->
                    ctx.principal().attribute(attr).map(PartitionId::new);
            case PartitionKeySpec.BodyField(String path) -> {
                if (body == null) {
                    yield Optional.empty();
                }
                try {
                    yield Optional.of(new PartitionId(Json.extractScalar(body, path)));
                } catch (RewriteException e) {
                    yield Optional.empty();
                }
            }
            case PartitionKeySpec.AnyOf(java.util.List<PartitionKeySpec> sources) -> {
                for (PartitionKeySpec source : sources) {
                    Optional<PartitionId> hit = tryResolve(source, ctx, body);
                    if (hit.isPresent()) {
                        yield hit;
                    }
                }
                yield Optional.empty();
            }
        };
    }
}
