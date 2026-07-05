package io.osproxy.jmh;

import com.fasterxml.jackson.databind.node.TextNode;
import io.osproxy.core.ClusterId;
import io.osproxy.core.Epoch;
import io.osproxy.core.IndexName;
import io.osproxy.core.PartitionId;
import io.osproxy.engine.Pipeline;
import io.osproxy.rewrite.Bulk;
import io.osproxy.rewrite.Fields;
import io.osproxy.rewrite.Json;
import io.osproxy.rewrite.Queries;
import io.osproxy.rewrite.RewriteException;
import io.osproxy.sink.MemorySink;
import io.osproxy.spi.DocIdRule;
import io.osproxy.spi.InjectedField;
import io.osproxy.spi.InjectedValue;
import io.osproxy.spi.PartitionKeySpec;
import io.osproxy.spi.Placement;
import io.osproxy.spi.PlacementAt;
import io.osproxy.spi.Principal;
import io.osproxy.spi.RequestCtx;
import io.osproxy.spi.TenancySpi;
import io.osproxy.tenancy.TenancyRouter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * Streaming vs. buffered, at two levels: the token-level transforms alone
 * (no pipe, no thread — just parser/generator vs. tree), and the full
 * {@code Pipeline} path used in production (which for the streaming case
 * spins up a {@code PipedOutputStream}/virtual-thread producer per call —
 * the one cost the transform-only numbers below don't include). Run both
 * families together to see where the pipe/thread handoff actually costs:
 *
 * <pre>./gradlew :osproxy-jmh:jmh -Pjmh.includes=Streaming</pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class StreamingTransformBench {

    private static final Map<String, com.fasterxml.jackson.databind.JsonNode> FILTER =
            Map.of("_tenant", TextNode.valueOf("acme"));

    private static byte[] doc(int docBytes) {
        String padding = "x".repeat(Math.max(1, docBytes - 40));
        return ("{\"msg\":\"" + padding + "\",\"n\":1}").getBytes(StandardCharsets.UTF_8);
    }

    // ---- transform-only: parser/generator vs. tree, no pipe, no thread ----

    @State(Scope.Benchmark)
    public static class DocState {
        @Param({"256", "4096", "65536"})
        int docBytes;

        byte[] doc;
        byte[] searchBody;

        @Setup
        public void build() {
            doc = doc(docBytes);
            String padding = "x".repeat(Math.max(1, docBytes - 40));
            searchBody = ("{\"query\":{\"match\":{\"msg\":\"" + padding + "\"}},\"size\":20}")
                    .getBytes(StandardCharsets.UTF_8);
        }
    }

    @State(Scope.Benchmark)
    public static class BulkState {
        @Param({"256", "4096"})
        int docBytes;

        @Param({"10", "100", "1000"})
        int bulkDocs;

        byte[] bulkPayload;

        @Setup
        public void build() {
            String line = new String(doc(docBytes), StandardCharsets.UTF_8);
            StringBuilder bulk = new StringBuilder(bulkDocs * (docBytes + 48));
            for (int i = 0; i < bulkDocs; i++) {
                bulk.append("{\"index\":{\"_index\":\"orders\",\"_id\":\"")
                        .append(i).append("\"}}\n");
                bulk.append(line).append('\n');
            }
            bulkPayload = bulk.toString().getBytes(StandardCharsets.UTF_8);
        }
    }

    @Benchmark
    public byte[] bufferedInjectAndSerialize(DocState state) throws RewriteException {
        var parsed = Json.parseObject(state.doc);
        Fields.injectFields(parsed, FILTER);
        return Json.writeBytes(parsed);
    }

    @Benchmark
    public byte[] streamingInjectFields(DocState state) throws Exception {
        var parser = Json.MAPPER.getFactory().createParser(state.doc);
        var out = new ByteArrayOutputStream(state.doc.length + 32);
        var generator = Json.MAPPER.getFactory().createGenerator(out);
        Fields.injectFieldsStreaming(parser, generator, FILTER);
        generator.close();
        return out.toByteArray();
    }

    @Benchmark
    public byte[] bufferedWrapQuery(DocState state) throws RewriteException {
        return Queries.wrapQuery(state.searchBody, FILTER);
    }

    @Benchmark
    public byte[] streamingWrapQuery(DocState state) throws Exception {
        var parser = Json.MAPPER.getFactory().createParser(state.searchBody);
        var out = new ByteArrayOutputStream(state.searchBody.length + 64);
        var generator = Json.MAPPER.getFactory().createGenerator(out);
        Queries.wrapQueryStreaming(parser, generator, FILTER);
        generator.close();
        return out.toByteArray();
    }

    @Benchmark
    public Object bufferedParseBulk(BulkState state) throws RewriteException {
        return Bulk.parseBulk(state.bulkPayload);
    }

    @Benchmark
    public int streamingParseBulkDrained(BulkState state) {
        var reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(
                        new ByteArrayInputStream(state.bulkPayload), StandardCharsets.UTF_8));
        var items = Bulk.parseBulkStream(reader);
        int count = 0;
        while (items.hasNext()) {
            items.next();
            count++;
        }
        return count;
    }

    // ---- full Pipeline path: includes the pipe + virtual-thread producer
    // the streaming ingest call spawns per request in production ----

    @State(Scope.Benchmark)
    public static class PipelineState {
        @Param({"256", "4096", "65536"})
        int docBytes;

        Pipeline pipeline;
        byte[] doc;
        int counter;

        @Setup
        public void build() {
            doc = doc(docBytes);
            var placement = new Placement.SharedIndex(
                    new ClusterId("c1"), new IndexName("shared"),
                    List.of(new InjectedField("_tenant", InjectedValue.PartitionIdValue.INSTANCE)));
            TenancySpi spi = new TenancySpi() {
                @Override
                public PartitionKeySpec partitionKeySpec() {
                    return new PartitionKeySpec.Header("x-tenant");
                }

                @Override
                public PlacementAt placementFor(PartitionId partition) {
                    return new PlacementAt(placement, Epoch.INITIAL);
                }

                @Override
                public Optional<DocIdRule> docIdRule() {
                    return Optional.of(new DocIdRule("{partition}:{id}", true));
                }
            };
            var sink = new MemorySink();
            pipeline = new Pipeline(new TenancyRouter(spi), sink, sink);
        }

        RequestCtx ctx(String id) {
            return new RequestCtx(
                    RequestCtx.HttpMethod.PUT, "/orders/_doc/" + id,
                    io.osproxy.core.EndpointKind.INGEST_DOC,
                    Optional.of("orders"), Optional.of(id),
                    List.of(Map.entry("x-tenant", "acme")), new byte[0],
                    new Principal("bench", Map.of("tenant", "acme")));
        }
    }

    // MemorySink never evicts, so a monotonically increasing id across a
    // 10s JMH iteration (millions of ops for small docs) would grow it
    // unbounded and OOM the fork; cycling through a small pool means every
    // op after the first lap is an overwrite (an Index, same cost shape),
    // keeping the sink's footprint bounded regardless of iteration length.
    private static final int ID_POOL = 1000;

    @Benchmark
    public Object pipelineIngestBuffered(PipelineState state) throws Exception {
        String id = "buf-" + (state.counter++ % ID_POOL);
        RequestCtx base = state.ctx(id);
        RequestCtx ctx = new RequestCtx(
                base.method(), base.path(), base.endpoint(),
                base.logicalIndex(), base.docId(), base.headers(), state.doc, base.principal());
        return state.pipeline.handle(ctx);
    }

    @Benchmark
    public Object pipelineIngestStreaming(PipelineState state) throws Exception {
        String id = "str-" + (state.counter++ % ID_POOL);
        return state.pipeline.ingestDocStreaming(
                state.ctx(id), new ByteArrayInputStream(state.doc));
    }
}
