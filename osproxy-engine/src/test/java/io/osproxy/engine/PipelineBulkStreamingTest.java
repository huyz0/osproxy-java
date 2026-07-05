package io.osproxy.engine;

import static io.osproxy.engine.PipelineTestSupport.pipeline;
import static io.osproxy.engine.PipelineTestSupport.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import io.osproxy.rewrite.Json;
import io.osproxy.rewrite.RewriteException;
import io.osproxy.sink.MemorySink;
import io.osproxy.spi.RequestCtx.HttpMethod;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The streaming twin of {@code PipelineSearchAndMultiTest}'s bulk cases,
 * driven through {@link Pipeline#openBulkStream} directly rather than
 * through {@code handle}, since that is the seam the ingress uses to avoid
 * buffering the body.
 */
class PipelineBulkStreamingTest {

    private MemorySink sink;
    private Pipeline pipeline;

    @BeforeEach
    void seed() {
        sink = new MemorySink();
        pipeline = pipeline(sink);
        pipeline.handle(request(
                HttpMethod.PUT, "/orders/_doc/1", "acme", "{\"msg\":\"hi\"}".getBytes()));
    }

    @Test
    void streamsAllVerbsAndDispatchesEachAsItParses() throws Exception {
        String ndjson = """
                {"index":{"_index":"orders","_id":"10"}}
                {"m":"a"}
                {"create":{"_index":"orders","_id":"11"}}
                {"m":"b"}
                {"update":{"_index":"orders","_id":"10"}}
                {"doc":{"m":"a2"}}
                {"delete":{"_index":"orders","_id":"11"}}
                """;
        JsonNode body = writeAndParse(ndjson);
        assertThat(body.get("errors").booleanValue()).isFalse();
        JsonNode items = body.get("items");
        assertThat(items).hasSize(4);
        assertThat(items.get(0).at("/index/_id").textValue()).isEqualTo("10");
        assertThat(items.get(0).at("/index/status").intValue()).isEqualTo(201);
        assertThat(items.get(2).at("/update/status").intValue()).isEqualTo(200);
        assertThat(items.get(3).at("/delete/status").intValue()).isEqualTo(200);

        JsonNode got = PipelineTestSupport.json(
                pipeline.handle(request(HttpMethod.GET, "/orders/_doc/10", "acme")));
        assertThat(got.at("/_source/m").textValue()).isEqualTo("a2");
    }

    @Test
    void perItemErrorsAreFlaggedNotWholesale() throws Exception {
        String ndjson = """
                {"create":{"_index":"orders","_id":"1"}}
                {"m":"dup"}
                {"index":{"_index":"orders","_id":"12"}}
                {"m":"ok"}
                """;
        JsonNode body = writeAndParse(ndjson);
        assertThat(body.get("errors").booleanValue()).isTrue();
        assertThat(body.at("/items/0/create/status").intValue()).isEqualTo(409);
        assertThat(body.at("/items/1/index/status").intValue()).isEqualTo(201);
    }

    @Test
    void anEmptyBodyIsRefusedBeforeAnyOutputIsWritten() {
        var ctx = request(HttpMethod.POST, "/_bulk", "acme", new byte[0]);
        assertThatThrownBy(() -> pipeline.openBulkStream(ctx, new ByteArrayInputStream(new byte[0])))
                .isInstanceOf(RewriteException.class);
    }

    @Test
    void aMalformedFirstLineIsRefusedBeforeAnyOutputIsWritten() {
        var ctx = request(HttpMethod.POST, "/_bulk", "acme", new byte[0]);
        byte[] malformed = "not json\n".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(
                () -> pipeline.openBulkStream(ctx, new ByteArrayInputStream(malformed)))
                .isInstanceOf(RewriteException.class);
    }

    private JsonNode writeAndParse(String ndjson) throws Exception {
        var ctx = request(HttpMethod.POST, "/_bulk", "acme", new byte[0]);
        var stream = pipeline.openBulkStream(
                ctx, new ByteArrayInputStream(ndjson.getBytes(StandardCharsets.UTF_8)));
        var out = new ByteArrayOutputStream();
        stream.writeTo(out);
        return Json.MAPPER.readTree(out.toByteArray());
    }
}
