package io.osproxy.engine;

import static io.osproxy.engine.PipelineTestSupport.json;
import static io.osproxy.engine.PipelineTestSupport.pipeline;
import static io.osproxy.engine.PipelineTestSupport.request;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.osproxy.sink.MemorySink;
import io.osproxy.spi.RequestCtx.HttpMethod;
import org.junit.jupiter.api.Test;

/** Doc CRUD through the pipeline: symmetry, isolation, fail-closed edges. */
class PipelineDocTest {

    @Test
    void ingestThenGetIsSymmetric() {
        var sink = new MemorySink();
        var pipeline = pipeline(sink);

        PipelineResponse put = pipeline.handle(request(
                HttpMethod.PUT, "/orders/_doc/7", "acme", "{\"msg\":\"hi\"}".getBytes()));
        assertThat(put.status()).isEqualTo(201);
        JsonNode ack = json(put);
        // The client sees its own labels, never the physical ones.
        assertThat(ack.get("_index").textValue()).isEqualTo("orders");
        assertThat(ack.get("_id").textValue()).isEqualTo("7");

        PipelineResponse got = pipeline.handle(request(
                HttpMethod.GET, "/orders/_doc/7", "acme"));
        assertThat(got.status()).isEqualTo(200);
        JsonNode doc = json(got);
        assertThat(doc.get("_index").textValue()).isEqualTo("orders");
        assertThat(doc.get("_id").textValue()).isEqualTo("7");
        assertThat(doc.at("/_source/msg").textValue()).isEqualTo("hi");
        // The injected isolation marker never reaches the client.
        assertThat(doc.at("/_source/_tenant").isMissingNode()).isTrue();
    }

    @Test
    void crossTenantGetIsNotFound() {
        var sink = new MemorySink();
        var pipeline = pipeline(sink);
        pipeline.handle(request(
                HttpMethod.PUT, "/orders/_doc/7", "acme", "{\"msg\":\"secret\"}".getBytes()));

        PipelineResponse other = pipeline.handle(request(
                HttpMethod.GET, "/orders/_doc/7", "globex"));
        assertThat(other.status()).isEqualTo(404);
        assertThat(new String(other.body())).doesNotContain("secret");
    }

    @Test
    void clientCannotSpoofTheInjectedMarker() {
        var sink = new MemorySink();
        var pipeline = pipeline(sink);
        pipeline.handle(request(
                HttpMethod.PUT, "/orders/_doc/7", "acme",
                "{\"msg\":\"x\",\"_tenant\":\"globex\"}".getBytes()));

        // The spoofed value was overwritten; globex still cannot see the doc.
        assertThat(pipeline.handle(request(HttpMethod.GET, "/orders/_doc/7", "globex")).status())
                .isEqualTo(404);
        assertThat(pipeline.handle(request(HttpMethod.GET, "/orders/_doc/7", "acme")).status())
                .isEqualTo(200);
    }

    @Test
    void deleteIsScopedToTheTenant() {
        var sink = new MemorySink();
        var pipeline = pipeline(sink);
        pipeline.handle(request(HttpMethod.PUT, "/orders/_doc/7", "acme", "{}".getBytes()));

        // globex's delete of the same logical id misses acme's document.
        assertThat(pipeline.handle(request(HttpMethod.DELETE, "/orders/_doc/7", "globex")).status())
                .isEqualTo(404);
        assertThat(pipeline.handle(request(HttpMethod.DELETE, "/orders/_doc/7", "acme")).status())
                .isEqualTo(200);
        assertThat(pipeline.handle(request(HttpMethod.GET, "/orders/_doc/7", "acme")).status())
                .isEqualTo(404);
    }

    @Test
    void staleEpochWritesAreRefusedWith409() {
        var sink = new MemorySink();
        var router = new io.osproxy.tenancy.TenancyRouter(
                PipelineTestSupport.sharedIndexSpi(false));
        var pipeline = new Pipeline(router, sink, sink);

        PipelineResponse put = pipeline.handle(request(
                HttpMethod.PUT, "/orders/_doc/7", "acme", "{}".getBytes()));
        assertThat(put.status()).isEqualTo(409);
        assertThat(new String(put.body())).contains("stale_epoch");
        // Reads still work under a write freeze.
        assertThat(pipeline.handle(request(HttpMethod.GET, "/orders/_doc/7", "acme")).status())
                .isEqualTo(404);
    }

    @Test
    void failClosedEdges() {
        var pipeline = pipeline(new MemorySink());
        // No tenant header -> partition_unresolved (400).
        var noTenant = new io.osproxy.spi.RequestCtx(
                HttpMethod.PUT, "/orders/_doc/7", io.osproxy.core.EndpointKind.INGEST_DOC,
                java.util.Optional.of("orders"), java.util.Optional.of("7"),
                java.util.List.of(), "{}".getBytes(), new io.osproxy.spi.Principal("anon"));
        PipelineResponse resp = pipeline.handle(noTenant);
        assertThat(resp.status()).isEqualTo(400);
        assertThat(new String(resp.body())).contains("partition_unresolved");

        // Malformed body -> 400. Unknown endpoint -> 400 unsupported.
        assertThat(pipeline.handle(request(
                        HttpMethod.PUT, "/orders/_doc/7", "acme", "not json".getBytes()))
                .status()).isEqualTo(400);
        assertThat(pipeline.handle(request(HttpMethod.GET, "/_cat/indices", "acme")).status())
                .isEqualTo(400);
        assertThat(pipeline.handle(request(HttpMethod.POST, "/_search/scroll", "acme")).status())
                .isEqualTo(400);
    }

    @Test
    void createRefusesDuplicatesAndAutoIdIngestWorks() {
        var sink = new MemorySink();
        var pipeline = pipeline(sink);
        assertThat(pipeline.handle(request(
                        HttpMethod.PUT, "/orders/_create/9", "acme", "{}".getBytes()))
                .status()).isEqualTo(201);
        assertThat(pipeline.handle(request(
                        HttpMethod.PUT, "/orders/_create/9", "acme", "{}".getBytes()))
                .status()).isEqualTo(409);

        PipelineResponse auto = pipeline.handle(request(
                HttpMethod.POST, "/orders/_doc", "acme", "{\"a\":1}".getBytes()));
        assertThat(auto.status()).isEqualTo(201);
        assertThat(json(auto).get("_id").textValue()).isNotBlank();
    }
}
