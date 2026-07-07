package io.osproxy.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.osproxy.core.ClusterId;
import io.osproxy.core.EndpointKind;
import io.osproxy.core.Epoch;
import io.osproxy.core.IndexName;
import io.osproxy.core.PartitionId;
import io.osproxy.rewrite.Json;
import io.osproxy.spi.BodyTransform;
import io.osproxy.spi.DocIdRule;
import io.osproxy.spi.InjectedField;
import io.osproxy.spi.InjectedValue;
import io.osproxy.spi.PartitionKeySpec;
import io.osproxy.spi.Placement;
import io.osproxy.spi.PlacementAt;
import io.osproxy.spi.Principal;
import io.osproxy.spi.RequestCtx;
import io.osproxy.spi.RouteDecision;
import io.osproxy.spi.SpiException;
import io.osproxy.spi.TenancySpi;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TenancyRouterTest {

    private static final List<InjectedField> INJECT =
            List.of(new InjectedField("_tenant", InjectedValue.PartitionIdValue.INSTANCE));

    private static RequestCtx ctx(String logicalIndex) {
        return new RequestCtx(
                RequestCtx.HttpMethod.POST,
                "/" + logicalIndex + "/_doc",
                EndpointKind.INGEST_DOC,
                Optional.of(logicalIndex),
                Optional.empty(),
                List.of(Map.entry("x-tenant", "acme")),
                new byte[0],
                new Principal("alice", Map.of("tenant", "acme")));
    }

    private static TenancySpi spi(Placement placement, Optional<DocIdRule> rule) {
        return new TenancySpi() {
            @Override
            public PartitionKeySpec partitionKeySpec() {
                return new PartitionKeySpec.Header("x-tenant");
            }

            @Override
            public PlacementAt placementFor(PartitionId partition) {
                return new PlacementAt(placement, new Epoch(2));
            }

            @Override
            public Optional<DocIdRule> docIdRule() {
                return rule;
            }
        };
    }

    @Test
    void sharedIndexRoutesToThePhysicalIndexWithInjectAndRule() throws Exception {
        var placement = new Placement.SharedIndex(
                new ClusterId("c1"), new IndexName("shared"), INJECT);
        var rule = new DocIdRule("{partition}:{id}", true);
        var router = new TenancyRouter(spi(placement, Optional.of(rule)));

        RouteDecision d = router.route(ctx("orders"), null);
        assertThat(d.target().index().value()).isEqualTo("shared");
        assertThat(d.partition().value()).isEqualTo("acme");
        assertThat(d.epoch().generation()).isEqualTo(2);
        assertThat(d.transform()).isInstanceOf(BodyTransform.Both.class);
    }

    @Test
    void sharedIndexWithoutInjectionFailsClosed() {
        var placement = new Placement.SharedIndex(
                new ClusterId("c1"), new IndexName("shared"), List.of());
        var router = new TenancyRouter(spi(placement, Optional.empty()));
        assertThatThrownBy(() -> router.route(ctx("orders"), null))
                .isInstanceOf(SpiException.IdRuleMissingPartition.class);
    }

    @Test
    void sharedIndexWithPartitionFreeIdRuleFailsClosed() {
        var placement = new Placement.SharedIndex(
                new ClusterId("c1"), new IndexName("shared"), INJECT);
        var router = new TenancyRouter(
                spi(placement, Optional.of(new DocIdRule("{id}", false))));
        assertThatThrownBy(() -> router.route(ctx("orders"), null))
                .isInstanceOf(SpiException.IdRuleMissingPartition.class);
    }

    @Test
    void sharedIndexWithoutIdRuleInjectsOnly() throws Exception {
        var placement = new Placement.SharedIndex(
                new ClusterId("c1"), new IndexName("shared"), INJECT);
        var router = new TenancyRouter(spi(placement, Optional.empty()));
        assertThat(router.route(ctx("orders"), null).transform())
                .isInstanceOf(BodyTransform.Inject.class);
    }

    @Test
    void upstreamCredentialsFromTheSpiReachTheTarget() throws Exception {
        var placement = new Placement.DedicatedIndex(new ClusterId("c1"), new IndexName("acme-idx"));
        var creds = io.osproxy.core.UpstreamCredentials.bearer("service-token");
        var spi = new TenancySpi() {
            @Override
            public PartitionKeySpec partitionKeySpec() {
                return new PartitionKeySpec.Header("x-tenant");
            }

            @Override
            public PlacementAt placementFor(PartitionId partition) {
                return new PlacementAt(placement, new Epoch(2));
            }

            @Override
            public Optional<io.osproxy.core.UpstreamCredentials> upstreamCredentials(
                    ClusterId cluster) {
                return cluster.value().equals("c1") ? Optional.of(creds) : Optional.empty();
            }
        };
        RouteDecision d = new TenancyRouter(spi).route(ctx("orders"), null);
        assertThat(d.target().credentials()).contains(creds);
    }

    @Test
    void dedicatedIndexUsesThePlacementIndexNoTransform() throws Exception {
        var placement = new Placement.DedicatedIndex(new ClusterId("c1"), new IndexName("acme-idx"));
        var router = new TenancyRouter(spi(placement, Optional.empty()));
        RouteDecision d = router.route(ctx("orders"), null);
        assertThat(d.target().index().value()).isEqualTo("acme-idx");
        assertThat(d.transform()).isSameAs(BodyTransform.None.INSTANCE);
    }

    @Test
    void dedicatedClusterPassesTheLogicalIndexThrough() throws Exception {
        var placement = new Placement.DedicatedCluster(new ClusterId("acme-cluster"));
        var router = new TenancyRouter(
                spi(placement, Optional.of(new DocIdRule("{id}", false))));
        RouteDecision d = router.route(ctx("orders"), null);
        assertThat(d.target().cluster().value()).isEqualTo("acme-cluster");
        assertThat(d.target().index().value()).isEqualTo("orders");
        assertThat(d.transform()).isInstanceOf(BodyTransform.ConstructId.class);
    }

    @Test
    void dedicatedClusterWithoutALogicalIndexIsUnsupported() {
        var placement = new Placement.DedicatedCluster(new ClusterId("c"));
        var router = new TenancyRouter(spi(placement, Optional.empty()));
        var noIndex = new RequestCtx(
                RequestCtx.HttpMethod.POST, "/_mget", EndpointKind.MULTI_GET,
                Optional.empty(), Optional.empty(),
                List.of(Map.entry("x-tenant", "acme")), new byte[0], new Principal("a"));
        assertThatThrownBy(() -> router.route(noIndex, null))
                .isInstanceOf(SpiException.UnsupportedEndpoint.class);
    }

    @Test
    void bodyFieldAndAnyOfResolution() throws Exception {
        var placement = new Placement.DedicatedIndex(new ClusterId("c"), new IndexName("i"));
        TenancySpi spi = new TenancySpi() {
            @Override
            public PartitionKeySpec partitionKeySpec() {
                return new PartitionKeySpec.AnyOf(List.of(
                        new PartitionKeySpec.BodyField("customer.tenant"),
                        new PartitionKeySpec.PrincipalAttr("tenant")));
            }

            @Override
            public PlacementAt placementFor(PartitionId partition) {
                return new PlacementAt(placement, Epoch.INITIAL);
            }
        };
        var router = new TenancyRouter(spi);

        var body = Json.parseObject("{\"customer\":{\"tenant\":\"globex\"}}".getBytes());
        assertThat(router.route(ctx("orders"), body).partition().value()).isEqualTo("globex");
        // Body source misses -> falls through to the principal attribute.
        assertThat(router.route(ctx("orders"), null).partition().value()).isEqualTo("acme");
    }

    @Test
    void unresolvedPartitionThrows() {
        var placement = new Placement.DedicatedIndex(new ClusterId("c"), new IndexName("i"));
        TenancySpi spi = new TenancySpi() {
            @Override
            public PartitionKeySpec partitionKeySpec() {
                return new PartitionKeySpec.Header("x-absent");
            }

            @Override
            public PlacementAt placementFor(PartitionId partition) {
                return new PlacementAt(placement, Epoch.INITIAL);
            }
        };
        assertThatThrownBy(() -> new TenancyRouter(spi).route(ctx("orders"), null))
                .isInstanceOf(SpiException.PartitionUnresolved.class);
    }

    @Test
    void routeCursorUsesThePlacementIndexOrAPlaceholder() throws Exception {
        var cursorCtx = new RequestCtx(
                RequestCtx.HttpMethod.POST, "/_search/scroll", EndpointKind.CURSOR,
                Optional.empty(), Optional.empty(),
                List.of(Map.entry("x-tenant", "acme")), new byte[0], new Principal("a"));

        var shared = new Placement.SharedIndex(
                new ClusterId("c1"), new IndexName("shared"), INJECT);
        assertThat(new TenancyRouter(spi(shared, Optional.empty()))
                .routeCursor(cursorCtx).target().index().value()).isEqualTo("shared");

        var dedicatedIdx = new Placement.DedicatedIndex(new ClusterId("c1"), new IndexName("di"));
        assertThat(new TenancyRouter(spi(dedicatedIdx, Optional.empty()))
                .routeCursor(cursorCtx).target().index().value()).isEqualTo("di");

        // Dedicated cluster + no index: the placeholder, never a fail-closed
        // refusal (the cursor call is cluster-scoped).
        var dedicated = new Placement.DedicatedCluster(new ClusterId("acme-c"));
        var d = new TenancyRouter(spi(dedicated, Optional.empty())).routeCursor(cursorCtx);
        assertThat(d.target().index().value()).isEqualTo("cursor");
        assertThat(d.target().cluster().value()).isEqualTo("acme-c");

        // With an index in the path, the logical index passes through.
        var indexed = new RequestCtx(
                RequestCtx.HttpMethod.POST, "/orders/_search/point_in_time",
                EndpointKind.CURSOR, Optional.of("orders"), Optional.empty(),
                List.of(Map.entry("x-tenant", "acme")), new byte[0], new Principal("a"));
        assertThat(new TenancyRouter(spi(dedicated, Optional.empty()))
                .routeCursor(indexed).target().index().value()).isEqualTo("orders");
    }
}
