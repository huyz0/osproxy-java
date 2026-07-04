package io.osproxy.engine;

import com.fasterxml.jackson.databind.JsonNode;
import io.osproxy.core.ClusterId;
import io.osproxy.core.EndpointKind;
import io.osproxy.core.Epoch;
import io.osproxy.core.IndexName;
import io.osproxy.core.PartitionId;
import io.osproxy.rewrite.Json;
import io.osproxy.sink.MemorySink;
import io.osproxy.spi.DocIdRule;
import io.osproxy.spi.InjectedField;
import io.osproxy.spi.InjectedValue;
import io.osproxy.spi.PartitionKeySpec;
import io.osproxy.spi.Placement;
import io.osproxy.spi.PlacementAt;
import io.osproxy.spi.Principal;
import io.osproxy.spi.RequestCtx;
import io.osproxy.spi.SpiException;
import io.osproxy.spi.TenancySpi;
import io.osproxy.tenancy.TenancyRouter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** A shared-index pipeline over a MemorySink — the harness every engine test uses. */
final class PipelineTestSupport {

    static final List<InjectedField> INJECT =
            List.of(new InjectedField("_tenant", InjectedValue.PartitionIdValue.INSTANCE));

    private PipelineTestSupport() {}

    /** A shared-index tenancy resolving the tenant from the x-tenant header. */
    static TenancySpi sharedIndexSpi(boolean admitWrites) {
        var placement = new Placement.SharedIndex(
                new ClusterId("c1"), new IndexName("shared"), INJECT);
        return new TenancySpi() {
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

            @Override
            public boolean admitWrite(PartitionId partition, Epoch epoch) {
                return admitWrites;
            }
        };
    }

    static Pipeline pipeline(MemorySink sink) {
        return new Pipeline(new TenancyRouter(sharedIndexSpi(true)), sink, sink);
    }

    static RequestCtx request(
            RequestCtx.HttpMethod method, String path, String tenant, byte[] body) {
        Classify.Classified c = Classify.classify(method, path);
        return new RequestCtx(
                method, path, c.endpoint(), c.logicalIndex(), c.docId(),
                List.of(Map.entry("x-tenant", tenant)), body,
                new Principal("user-" + tenant, Map.of("tenant", tenant)));
    }

    static RequestCtx request(RequestCtx.HttpMethod method, String path, String tenant) {
        return request(method, path, tenant, new byte[0]);
    }

    static JsonNode json(PipelineResponse response) {
        try {
            return Json.MAPPER.readTree(response.body());
        } catch (java.io.IOException e) {
            throw new AssertionError("response body is not json", e);
        }
    }
}
