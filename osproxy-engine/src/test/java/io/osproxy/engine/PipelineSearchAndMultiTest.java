package io.osproxy.engine;

import static io.osproxy.engine.PipelineTestSupport.json;
import static io.osproxy.engine.PipelineTestSupport.pipeline;
import static io.osproxy.engine.PipelineTestSupport.request;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.osproxy.sink.MemorySink;
import io.osproxy.spi.RequestCtx.HttpMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Search/count/bulk/mget/msearch through the pipeline. */
class PipelineSearchAndMultiTest {

    private MemorySink sink;
    private Pipeline pipeline;

    @BeforeEach
    void seed() {
        sink = new MemorySink();
        pipeline = pipeline(sink);
        pipeline.handle(request(
                HttpMethod.PUT, "/orders/_doc/1", "acme", "{\"msg\":\"hi\"}".getBytes()));
        pipeline.handle(request(
                HttpMethod.PUT, "/orders/_doc/1", "globex", "{\"msg\":\"other\"}".getBytes()));
    }

    @Test
    void searchSeesOnlyTheTenantsDocumentsWithLogicalLabels() {
        PipelineResponse resp = pipeline.handle(request(
                HttpMethod.POST, "/orders/_search", "acme",
                "{\"query\":{\"match_all\":{}}}".getBytes()));
        assertThat(resp.status()).isEqualTo(200);
        JsonNode hits = json(resp).at("/hits/hits");
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).get("_id").textValue()).isEqualTo("1");
        assertThat(hits.get(0).get("_index").textValue()).isEqualTo("orders");
        assertThat(hits.get(0).at("/_source/_tenant").isMissingNode()).isTrue();
        assertThat(hits.get(0).at("/_source/msg").textValue()).isEqualTo("hi");
    }

    @Test
    void countIsTenantScoped() {
        PipelineResponse resp = pipeline.handle(request(
                HttpMethod.POST, "/orders/_count", "acme", new byte[0]));
        assertThat(json(resp).get("count").intValue()).isEqualTo(1);
    }

    @Test
    void unfilterableQueriesAreRefused() {
        PipelineResponse resp = pipeline.handle(request(
                HttpMethod.POST, "/orders/_search", "acme",
                "{\"suggest\":{\"s\":{\"text\":\"q\"}}}".getBytes()));
        assertThat(resp.status()).isEqualTo(400);
        assertThat(new String(resp.body())).contains("malformed_request");
    }

    @Test
    void bulkAllVerbsRoundTripWithLogicalIds() {
        String ndjson = """
                {"index":{"_index":"orders","_id":"10"}}
                {"m":"a"}
                {"create":{"_index":"orders","_id":"11"}}
                {"m":"b"}
                {"update":{"_index":"orders","_id":"10"}}
                {"doc":{"m":"a2"}}
                {"delete":{"_index":"orders","_id":"11"}}
                """;
        PipelineResponse resp = pipeline.handle(request(
                HttpMethod.POST, "/_bulk", "acme", ndjson.getBytes()));
        assertThat(resp.status()).isEqualTo(200);
        JsonNode body = json(resp);
        assertThat(body.get("errors").booleanValue()).isFalse();
        JsonNode items = body.get("items");
        assertThat(items).hasSize(4);
        assertThat(items.get(0).at("/index/_id").textValue()).isEqualTo("10");
        assertThat(items.get(0).at("/index/status").intValue()).isEqualTo(201);
        assertThat(items.get(2).at("/update/status").intValue()).isEqualTo(200);
        assertThat(items.get(3).at("/delete/status").intValue()).isEqualTo(200);

        // The updated doc reads back merged, isolated, and label-clean.
        JsonNode got = json(pipeline.handle(request(HttpMethod.GET, "/orders/_doc/10", "acme")));
        assertThat(got.at("/_source/m").textValue()).isEqualTo("a2");
    }

    @Test
    void bulkErrorsAreFlaggedPerItemNotWholesale() {
        String ndjson = """
                {"create":{"_index":"orders","_id":"1"}}
                {"m":"dup"}
                {"index":{"_index":"orders","_id":"12"}}
                {"m":"ok"}
                """;
        // acme:1 already exists from seed -> create conflicts, index succeeds.
        PipelineResponse resp = pipeline.handle(request(
                HttpMethod.POST, "/_bulk", "acme", ndjson.getBytes()));
        JsonNode body = json(resp);
        assertThat(body.get("errors").booleanValue()).isTrue();
        assertThat(body.at("/items/0/create/status").intValue()).isEqualTo(409);
        assertThat(body.at("/items/1/index/status").intValue()).isEqualTo(201);
    }

    @Test
    void mgetReinterleavesInRequestOrderAcrossPresentAndAbsent() {
        PipelineResponse resp = pipeline.handle(request(
                HttpMethod.POST, "/_mget", "acme",
                "{\"docs\":[{\"_index\":\"orders\",\"_id\":\"absent\"},{\"_index\":\"orders\",\"_id\":\"1\"}]}"
                        .getBytes()));
        JsonNode docs = json(resp).get("docs");
        assertThat(docs).hasSize(2);
        assertThat(docs.get(0).get("found").booleanValue()).isFalse();
        assertThat(docs.get(0).get("_id").textValue()).isEqualTo("absent");
        assertThat(docs.get(1).get("found").booleanValue()).isTrue();
        assertThat(docs.get(1).at("/_source/msg").textValue()).isEqualTo("hi");
        assertThat(docs.get(1).at("/_source/_tenant").isMissingNode()).isTrue();
    }

    @Test
    void msearchAnswersEachSearchTenantScoped() {
        String ndjson = """
                {"index":"orders"}
                {"query":{"match_all":{}}}
                {"index":"orders"}
                {"query":{"match":{"msg":"nope"}}}
                """;
        PipelineResponse resp = pipeline.handle(request(
                HttpMethod.POST, "/_msearch", "acme", ndjson.getBytes()));
        JsonNode responses = json(resp).get("responses");
        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).at("/hits/hits")).hasSize(1);
        assertThat(responses.get(0).at("/hits/hits/0/_id").textValue()).isEqualTo("1");
        assertThat(responses.get(1).at("/hits/hits")).isEmpty();
    }

    @Test
    void bulkWithoutAnyIndexIsRefusedAndPathIndexIsTheFallback() {
        // No _index in the action and no index in the path -> 400.
        PipelineResponse resp = pipeline.handle(request(
                HttpMethod.POST, "/_bulk", "acme",
                "{\"index\":{\"_id\":\"1\"}}\n{\"m\":1}\n".getBytes()));
        assertThat(resp.status()).isEqualTo(400);

        // Path index fills in for actions without one.
        PipelineResponse ok = pipeline.handle(request(
                HttpMethod.POST, "/orders/_bulk", "acme",
                "{\"index\":{\"_id\":\"33\"}}\n{\"m\":1}\n".getBytes()));
        assertThat(json(ok).at("/items/0/index/_index").textValue()).isEqualTo("orders");
    }
}
