package io.osproxy.jmh;

import com.fasterxml.jackson.databind.node.TextNode;
import io.osproxy.rewrite.Bulk;
import io.osproxy.rewrite.Fields;
import io.osproxy.rewrite.Json;
import io.osproxy.rewrite.Queries;
import io.osproxy.rewrite.RewriteException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;

/**
 * The hot transforms across the dimensions that matter: document size
 * (256B/4KiB/64KiB), bulk batch size (10/100/1000 docs), and thread count
 * (the {@code contended*} variants run on 8 threads — the transforms are
 * stateless, so this pins the shared-{@code ObjectMapper} scaling). Run
 * with the gc profiler to watch how allocations/op grow with each
 * dimension:
 *
 * <pre>./gradlew :osproxy-jmh:jmh -Pjmh.includes=Dimensional</pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class DimensionalTransformBench {

    private static final Map<String, com.fasterxml.jackson.databind.JsonNode> FILTER =
            Map.of("_tenant", TextNode.valueOf("acme"));

    /** A document whose serialized size is ~docBytes, dominated by one field. */
    private static byte[] doc(int docBytes) {
        String padding = "x".repeat(Math.max(1, docBytes - 40));
        return ("{\"msg\":\"" + padding + "\",\"n\":1}").getBytes(StandardCharsets.UTF_8);
    }

    /** Doc-size dimension only (bulk size does not apply). */
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

    /** Doc-size x batch-size dimensions. */
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
    public Object parseBulk(BulkState state) throws RewriteException {
        return Bulk.parseBulk(state.bulkPayload);
    }

    @Benchmark
    public byte[] wrapQuery(DocState state) throws RewriteException {
        return Queries.wrapQuery(state.searchBody, FILTER);
    }

    @Benchmark
    public byte[] injectAndSerialize(DocState state) throws RewriteException {
        var parsed = Json.parseObject(state.doc);
        Fields.injectFields(parsed, FILTER);
        return Json.writeBytes(parsed);
    }

    @Benchmark
    @Threads(8)
    public Object contendedParseBulk(BulkState state) throws RewriteException {
        return Bulk.parseBulk(state.bulkPayload);
    }

    @Benchmark
    @Threads(8)
    public byte[] contendedWrapQuery(DocState state) throws RewriteException {
        return Queries.wrapQuery(state.searchBody, FILTER);
    }

    @Benchmark
    @Threads(8)
    public byte[] contendedInjectAndSerialize(DocState state) throws RewriteException {
        var parsed = Json.parseObject(state.doc);
        Fields.injectFields(parsed, FILTER);
        return Json.writeBytes(parsed);
    }
}
