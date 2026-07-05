package io.osproxy.rewrite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FieldsAndQueriesTest {

    @Test
    void injectOverwritesClientSpoofAndStripRemoves() throws Exception {
        var doc = Json.parseObject("{\"msg\":\"hi\",\"_tenant\":\"spoofed\"}".getBytes());
        Fields.injectFields(doc, Map.of("_tenant", TextNode.valueOf("acme")));
        assertThat(doc.get("_tenant").textValue()).isEqualTo("acme");

        int stripped = Fields.stripFields(doc, List.of("_tenant", "absent"));
        assertThat(stripped).isEqualTo(1);
        assertThat(doc.has("_tenant")).isFalse();
        assertThat(doc.get("msg").textValue()).isEqualTo("hi");
    }

    @Test
    void injectFieldsStreamingCopiesNestedStructuresAndAppendsFields() throws Exception {
        String source = "{\"a\":1,\"nested\":{\"b\":[1,2,{\"c\":\"d\"}]},\"arr\":[]}";
        var parser = Json.MAPPER.getFactory().createParser(source.getBytes());
        var out = new java.io.ByteArrayOutputStream();
        var generator = Json.MAPPER.getFactory().createGenerator(out);
        Fields.injectFieldsStreaming(parser, generator, Map.of("_tenant", TextNode.valueOf("acme")));
        generator.close();

        JsonNode doc = Json.MAPPER.readTree(out.toByteArray());
        assertThat(doc.get("a").intValue()).isEqualTo(1);
        assertThat(doc.at("/nested/b/2/c").textValue()).isEqualTo("d");
        assertThat(doc.get("arr").isArray()).isTrue();
        assertThat(doc.get("_tenant").textValue()).isEqualTo("acme");
    }

    @Test
    void injectFieldsStreamingLastFieldWinsOnAClientSpoofedName() throws Exception {
        String source = "{\"_tenant\":\"spoofed\",\"msg\":\"hi\"}";
        var parser = Json.MAPPER.getFactory().createParser(source.getBytes());
        var out = new java.io.ByteArrayOutputStream();
        var generator = Json.MAPPER.getFactory().createGenerator(out);
        Fields.injectFieldsStreaming(parser, generator, Map.of("_tenant", TextNode.valueOf("acme")));
        generator.close();

        // Last-field-wins JSON parsing resolves the duplicate key to the
        // injected value, exactly like the buffered injectFields overwrite.
        JsonNode doc = Json.MAPPER.readTree(out.toByteArray());
        assertThat(doc.get("_tenant").textValue()).isEqualTo("acme");
    }

    @Test
    void injectFieldsStreamingRefusesANonObjectTopLevel() {
        assertThatThrownBy(() -> {
            var p = Json.MAPPER.getFactory().createParser("[1,2]".getBytes());
            var g = Json.MAPPER.getFactory().createGenerator(new java.io.ByteArrayOutputStream());
            Fields.injectFieldsStreaming(p, g, Map.of());
        }).isInstanceOf(java.io.IOException.class);
    }

    @Test
    void wrapQueryEnclosesClientQueryAndPinsFilter() throws Exception {
        byte[] wrapped = Queries.wrapQuery(
                "{\"query\":{\"match\":{\"msg\":\"hi\"}},\"size\":5}".getBytes(),
                Map.of("_tenant", TextNode.valueOf("acme")));
        JsonNode doc = Json.MAPPER.readTree(wrapped);
        assertThat(doc.at("/query/bool/must/0/match/msg").textValue()).isEqualTo("hi");
        assertThat(doc.at("/query/bool/filter/0/term/_tenant").textValue()).isEqualTo("acme");
        assertThat(doc.get("size").intValue()).isEqualTo(5);
    }

    @Test
    void absentQueryBecomesMatchAllInsideTheEnclosure() throws Exception {
        byte[] wrapped = Queries.wrapQuery(
                new byte[0], Map.of("_tenant", TextNode.valueOf("acme")));
        JsonNode doc = Json.MAPPER.readTree(wrapped);
        assertThat(doc.at("/query/bool/must/0").has("match_all")).isTrue();
        assertThat(doc.at("/query/bool/filter/0/term/_tenant").textValue()).isEqualTo("acme");
    }

    @Test
    void emptyFilterPassesBodyThroughUntouched() throws Exception {
        byte[] body = "{\"query\":{\"match_all\":{}}}".getBytes();
        assertThat(Queries.wrapQuery(body, Map.of())).isSameAs(body);
    }

    @Test
    void unfilterableConstructsAreRefusedOnlyUnderAFilter() throws Exception {
        byte[] globalAgg =
                "{\"aggs\":{\"all\":{\"global\":{},\"aggs\":{\"n\":{\"value_count\":{\"field\":\"x\"}}}}}}"
                        .getBytes();
        byte[] suggest = "{\"suggest\":{\"s\":{\"text\":\"q\"}}}".getBytes();
        var filter = Map.<String, JsonNode>of("_tenant", TextNode.valueOf("acme"));

        for (byte[] body : List.of(globalAgg, suggest)) {
            assertThatThrownBy(() -> Queries.wrapQuery(body, filter))
                    .isInstanceOf(RewriteException.class)
                    .extracting(e -> ((RewriteException) e).kind())
                    .isEqualTo(RewriteException.Kind.UNFILTERABLE);
            // Dedicated placement (no filter): nothing to protect, passes through.
            assertThat(Queries.wrapQuery(body, Map.of())).isSameAs(body);
        }
    }

    @Test
    void nestedGlobalAggIsFoundAndMalformedBodiesRefused() {
        byte[] nested =
                "{\"aggs\":{\"outer\":{\"terms\":{\"field\":\"a\"},\"aggs\":{\"inner\":{\"global\":{}}}}}}"
                        .getBytes();
        assertThatThrownBy(() -> Queries.wrapQuery(
                        nested, Map.of("_t", TextNode.valueOf("x"))))
                .isInstanceOf(RewriteException.class);
        assertThatThrownBy(() -> Queries.wrapQuery(
                        "not json".getBytes(), Map.of("_t", TextNode.valueOf("x"))))
                .isInstanceOf(RewriteException.class)
                .extracting(e -> ((RewriteException) e).kind())
                .isEqualTo(RewriteException.Kind.INVALID_JSON);
        assertThatThrownBy(() -> Queries.wrapQuery(
                        "[1,2]".getBytes(), Map.of("_t", TextNode.valueOf("x"))))
                .isInstanceOf(RewriteException.class)
                .extracting(e -> ((RewriteException) e).kind())
                .isEqualTo(RewriteException.Kind.NOT_AN_OBJECT);
    }
}
