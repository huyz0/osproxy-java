package io.osproxy.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.osproxy.core.PartitionId;
import io.osproxy.rewrite.Json;
import io.osproxy.spi.BodyTransform;
import io.osproxy.spi.DocIdRule;
import io.osproxy.spi.InjectedField;
import io.osproxy.spi.InjectedValue;
import io.osproxy.spi.RequestCtx;
import io.osproxy.spi.SpiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Per-request resolution of a decision's {@link BodyTransform}: which fields
 * to inject (write) / strip and filter on (read), and the id rule. One place
 * derives both directions, so write↔read symmetry holds by construction.
 */
final class Transforms {

    private Transforms() {}

    /** The transform's id rule, when one applies. */
    static Optional<DocIdRule> idRule(BodyTransform transform) {
        return switch (transform) {
            case BodyTransform.ConstructId(DocIdRule rule) -> Optional.of(rule);
            case BodyTransform.Both(var ignored, DocIdRule rule) -> Optional.of(rule);
            case BodyTransform.None ignored -> Optional.empty();
            case BodyTransform.Inject ignored -> Optional.empty();
        };
    }

    /** The transform's injected fields, when any apply. */
    static List<InjectedField> injectedFields(BodyTransform transform) {
        return switch (transform) {
            case BodyTransform.Inject(List<InjectedField> fields) -> fields;
            case BodyTransform.Both(List<InjectedField> fields, var ignored) -> fields;
            case BodyTransform.None ignored -> List.of();
            case BodyTransform.ConstructId ignored -> List.of();
        };
    }

    /**
     * Resolves each injected field's value for this request. Insertion order
     * is preserved so the injected document and the read filter agree.
     */
    static Map<String, JsonNode> resolveInjected(
            List<InjectedField> fields, PartitionId partition, RequestCtx ctx)
            throws SpiException {
        Map<String, JsonNode> resolved = new LinkedHashMap<>();
        for (InjectedField field : fields) {
            resolved.put(field.name(), resolveValue(field.value(), partition, ctx));
        }
        return resolved;
    }

    private static JsonNode resolveValue(
            InjectedValue value, PartitionId partition, RequestCtx ctx) throws SpiException {
        return switch (value) {
            case InjectedValue.PartitionIdValue ignored -> TextNode.valueOf(partition.value());
            case InjectedValue.Constant(String literal) -> parseLiteral(literal);
            case InjectedValue.FromPrincipal(String attr) -> TextNode.valueOf(
                    ctx.principal().attribute(attr)
                            .orElseThrow(() -> new SpiException.PrincipalAttrMissing(attr)));
            case InjectedValue.FromHeader(String header) -> TextNode.valueOf(
                    ctx.header(header)
                            .orElseThrow(() -> new SpiException.HeaderMissing(header)));
        };
    }

    private static JsonNode parseLiteral(String literal) {
        try {
            return Json.MAPPER.readTree(literal);
        } catch (java.io.IOException e) {
            // A constant is operator config; a bad literal is a deployment bug.
            throw new IllegalStateException("invalid constant json literal", e);
        }
    }
}
