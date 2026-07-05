package io.osproxy.sink;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.osproxy.core.ClusterId;
import io.osproxy.core.Epoch;
import io.osproxy.core.IndexName;
import io.osproxy.core.Target;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MemorySinkTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final Target T =
            new Target(new ClusterId("c1"), new IndexName("shared"));

    private static WriteBatch.Op op(DocOp docOp) {
        return new WriteBatch.Op(T, docOp, Epoch.INITIAL);
    }

    @Test
    void writeStreamingWrapsAReadFailureAsAMalformedRequest() {
        var sink = new MemorySink();
        var brokenBody = new java.io.InputStream() {
            @Override
            public int read() throws java.io.IOException {
                throw new java.io.IOException("boom");
            }
        };
        org.junit.jupiter.api.Assertions.assertThrows(SinkException.class,
                () -> sink.writeStreaming(T, false, "acme:1", brokenBody, Optional.empty()));
    }

    @Test
    void searchAndCountStreamingDelegateToTheBufferedPaths() throws Exception {
        var sink = new MemorySink();
        sink.write(List.of(op(new DocOp.Index("1", "{\"m\":\"x\"}".getBytes(), Optional.empty()))));

        var searchResult = sink.searchStreaming(T, new java.io.ByteArrayInputStream(
                "{\"query\":{\"match_all\":{}}}".getBytes()));
        assertThat(searchResult.status()).isEqualTo(200);

        var countResult = sink.countStreaming(T, new java.io.ByteArrayInputStream(new byte[0]));
        assertThat(M.readTree(countResult.body()).get("count").intValue()).isEqualTo(1);
    }

    @Test
    void aSinkThatDoesNotOverrideWriteStreamingFailsClosed() {
        Sink bare = ops -> new WriteBatch.Ack(List.of());
        org.junit.jupiter.api.Assertions.assertThrows(SinkException.class, () ->
                bare.writeStreaming(T, false, "acme:1",
                        new java.io.ByteArrayInputStream(new byte[0]), Optional.empty()));
    }

    @Test
    void writeStreamingDelegatesToTheOrdinaryIndexAndCreatePaths() throws Exception {
        var sink = new MemorySink();

        var result = sink.writeStreaming(
                T, false, "acme:1",
                new java.io.ByteArrayInputStream("{\"v\":1}".getBytes()), Optional.empty());
        assertThat(result.status()).isEqualTo(201);

        var conflict = sink.writeStreaming(
                T, true, "acme:1",
                new java.io.ByteArrayInputStream("{\"v\":2}".getBytes()), Optional.empty());
        assertThat(conflict.status()).isEqualTo(409);

        assertThat(new String(sink.get(T, "acme:1", Optional.empty()).body()))
                .contains("\"v\":1");
    }

    @Test
    void indexCreateUpdateDeleteLifecycle() throws Exception {
        var sink = new MemorySink();

        var ack = sink.write(List.of(
                op(new DocOp.Index("acme:1", "{\"v\":1}".getBytes(), Optional.empty()))));
        assertThat(ack.results().get(0).status()).isEqualTo(201);

        // Re-index same id: 200 updated. Create on existing id: 409.
        ack = sink.write(List.of(
                op(new DocOp.Index("acme:1", "{\"v\":2}".getBytes(), Optional.empty())),
                op(new DocOp.Create("acme:1", "{\"v\":9}".getBytes(), Optional.empty()))));
        assertThat(ack.results().get(0).status()).isEqualTo(200);
        assertThat(ack.results().get(1).status()).isEqualTo(409);

        // Partial update merges fields.
        sink.write(List.of(op(new DocOp.Update(
                "acme:1", "{\"doc\":{\"extra\":\"x\"}}".getBytes(), Optional.empty()))));
        Reader.Response got = sink.get(T, "acme:1", Optional.empty());
        JsonNode source = M.readTree(got.body()).get("_source");
        assertThat(source.get("v").intValue()).isEqualTo(2);
        assertThat(source.get("extra").textValue()).isEqualTo("x");

        // Delete, then get → 404, update → 404, delete again → 404.
        ack = sink.write(List.of(op(new DocOp.Delete("acme:1", Optional.empty()))));
        assertThat(ack.results().get(0).ok()).isTrue();
        assertThat(sink.get(T, "acme:1", Optional.empty()).status()).isEqualTo(404);
        ack = sink.write(List.of(
                op(new DocOp.Update("acme:1", "{\"doc\":{}}".getBytes(), Optional.empty())),
                op(new DocOp.Delete("acme:1", Optional.empty()))));
        assertThat(ack.results().get(0).status()).isEqualTo(404);
        assertThat(ack.results().get(1).status()).isEqualTo(404);
    }

    @Test
    void searchEvaluatesTheProxyGeneratedQueryShape() throws Exception {
        var sink = new MemorySink();
        sink.write(List.of(
                op(new DocOp.Index("acme:1", "{\"_t\":\"acme\",\"m\":\"hi\"}".getBytes(), Optional.empty())),
                op(new DocOp.Index("globex:1", "{\"_t\":\"globex\",\"m\":\"hi\"}".getBytes(), Optional.empty()))));

        // The enclosure the proxy generates: bool { must: match_all, filter: term }.
        byte[] wrapped =
                "{\"query\":{\"bool\":{\"must\":[{\"match_all\":{}}],\"filter\":[{\"term\":{\"_t\":\"acme\"}}]}}}"
                        .getBytes();
        JsonNode hits = M.readTree(sink.search(T, wrapped).body()).at("/hits/hits");
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).get("_id").textValue()).isEqualTo("acme:1");

        JsonNode count = M.readTree(sink.count(T, wrapped).body());
        assertThat(count.get("count").intValue()).isEqualTo(1);

        // Empty body = match all in the target index.
        assertThat(M.readTree(sink.search(T, new byte[0]).body()).at("/hits/total/value").intValue())
                .isEqualTo(2);

        // A different index sees nothing.
        var other = new Target(new ClusterId("c1"), new IndexName("other"));
        assertThat(M.readTree(sink.search(other, new byte[0]).body()).at("/hits/total/value").intValue())
                .isZero();
    }

    @Test
    void termWithValueObjectAndUnknownClausesAndMatchEquality() throws Exception {
        var sink = new MemorySink();
        sink.write(List.of(op(new DocOp.Index(
                "a:1", "{\"_t\":\"a\",\"n\":5}".getBytes(), Optional.empty()))));

        byte[] termValueForm = "{\"query\":{\"term\":{\"_t\":{\"value\":\"a\"}}}}".getBytes();
        assertThat(count(sink, termValueForm)).isEqualTo(1);
        byte[] matchForm = "{\"query\":{\"match\":{\"n\":5}}}".getBytes();
        assertThat(count(sink, matchForm)).isEqualTo(1);
        byte[] unknown = "{\"query\":{\"prefix\":{\"_t\":\"a\"}}}".getBytes();
        assertThat(count(sink, unknown)).isZero();
        byte[] mismatch = "{\"query\":{\"term\":{\"_t\":\"b\"}}}".getBytes();
        assertThat(count(sink, mismatch)).isZero();
    }

    private static int count(MemorySink sink, byte[] body) throws Exception {
        return M.readTree(sink.count(T, body).body()).get("count").intValue();
    }

    @Test
    void cursorEmulationOneBatchThenEmpty() throws Exception {
        var sink = new MemorySink();
        sink.write(List.of(op(new DocOp.Index(
                "a:1", "{\"m\":1}".getBytes(), Optional.empty()))));

        var first = M.readTree(sink.searchScroll(T, new byte[0], "1m").body());
        String scrollId = first.get("_scroll_id").textValue();
        assertThat(first.at("/hits/hits")).hasSize(1);

        var next = M.readTree(sink.scrollNext(
                T, ("{\"scroll_id\":\"" + scrollId + "\"}").getBytes()).body());
        assertThat(next.at("/hits/hits")).isEmpty();
        assertThat(sink.scrollNext(T, "{\"scroll_id\":\"bogus\"}".getBytes()).status())
                .isEqualTo(404);
        assertThat(sink.scrollDelete(
                T, ("{\"scroll_id\":\"" + scrollId + "\"}").getBytes()).ok()).isTrue();
    }

    @Test
    void pitEmulationEchoesTheIdAndSearches() throws Exception {
        var sink = new MemorySink();
        sink.write(List.of(op(new DocOp.Index(
                "a:1", "{\"m\":1}".getBytes(), Optional.empty()))));
        var opened = M.readTree(sink.pitOpen(T, "1m").body());
        String pit = opened.get("pit_id").textValue();

        var result = M.readTree(sink.searchIndexless(
                T, ("{\"pit\":{\"id\":\"" + pit + "\"}}").getBytes()).body());
        assertThat(result.get("pit_id").textValue()).isEqualTo(pit);
        assertThat(result.at("/hits/hits")).hasSize(1);

        assertThat(sink.searchIndexless(T, "{}".getBytes()).ok()).isTrue();
        assertThat(sink.pitClose(
                T, ("{\"pit_id\":[\"" + pit + "\"]}").getBytes()).ok()).isTrue();
    }
}
