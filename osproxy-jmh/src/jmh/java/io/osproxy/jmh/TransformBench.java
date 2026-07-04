package io.osproxy.jmh;

import com.fasterxml.jackson.databind.node.TextNode;
import io.osproxy.rewrite.Bulk;
import io.osproxy.rewrite.DocIds;
import io.osproxy.rewrite.Queries;
import io.osproxy.rewrite.RewriteException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

/**
 * The hot transforms: bulk NDJSON parsing, query wrapping, id mapping.
 * Run with the gc profiler to watch allocations/op — the flat-memory goal
 * is guarded here, not guessed at.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
public class TransformBench {

    private static final byte[] BULK_100;
    private static final byte[] SEARCH_BODY =
            "{\"query\":{\"match\":{\"msg\":\"hello\"}},\"size\":20,\"sort\":[{\"ts\":\"desc\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
    private static final Map<String, com.fasterxml.jackson.databind.JsonNode> FILTER =
            Map.of("_tenant", TextNode.valueOf("acme"));

    static {
        StringBuilder bulk = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            bulk.append("{\"index\":{\"_index\":\"orders\",\"_id\":\"").append(i).append("\"}}\n");
            bulk.append("{\"msg\":\"hello world ").append(i).append("\",\"n\":").append(i).append("}\n");
        }
        BULK_100 = bulk.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Benchmark
    public Object parseBulk100Docs() throws RewriteException {
        return Bulk.parseBulk(BULK_100);
    }

    @Benchmark
    public byte[] wrapSearchQuery() throws RewriteException {
        return Queries.wrapQuery(SEARCH_BODY, FILTER);
    }

    @Benchmark
    public String mapLogicalToPhysicalId() throws RewriteException {
        return DocIds.mapLogicalToPhysical("{partition}:{id}", "acme", "order-12345");
    }
}
