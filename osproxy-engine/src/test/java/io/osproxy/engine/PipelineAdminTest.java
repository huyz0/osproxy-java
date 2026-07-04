package io.osproxy.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.osproxy.core.ClusterId;
import io.osproxy.core.Target;
import io.osproxy.sink.MemorySink;
import io.osproxy.sink.Reader;
import io.osproxy.sink.SinkException;
import io.osproxy.sink.WriteBatch;
import io.osproxy.spi.Principal;
import io.osproxy.spi.RequestCtx;
import io.osproxy.spi.RequestCtx.HttpMethod;
import io.osproxy.tenancy.TenancyRouter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Admin pass-through: refused without a policy, allow-listed with one. */
class PipelineAdminTest {

    /** Delegates writes/reads to a MemorySink; records verbatim-forward calls. */
    private static final class RecordingForwarder implements io.osproxy.sink.Sink, Reader {
        private final MemorySink delegate = new MemorySink();
        Target lastTarget;
        RequestCtx.HttpMethod lastMethod;
        String lastPath;

        @Override
        public WriteBatch.Ack write(List<WriteBatch.Op> ops) throws SinkException {
            return delegate.write(ops);
        }

        @Override
        public Response get(Target t, String id, Optional<String> r) throws SinkException {
            return delegate.get(t, id, r);
        }

        @Override
        public Response search(Target t, byte[] b) throws SinkException {
            return delegate.search(t, b);
        }

        @Override
        public Response count(Target t, byte[] b) throws SinkException {
            return delegate.count(t, b);
        }

        @Override
        public Response forward(
                Target target, RequestCtx.HttpMethod method, String path, String query,
                byte[] body, List<Map.Entry<String, String>> extraHeaders) {
            this.lastTarget = target;
            this.lastMethod = method;
            this.lastPath = path;
            return new Response(200, "{\"cluster_name\":\"ops\"}".getBytes());
        }
    }

    private static RequestCtx adminRequest(String path) {
        HttpMethod method = HttpMethod.GET;
        Classify.Classified c = Classify.classify(method, path);
        return new RequestCtx(
                method, path, c.endpoint(), c.logicalIndex(), c.docId(),
                List.of(), new byte[0], new Principal("ops-caller", Map.of()));
    }

    @Test
    void refusedWithNoAdminPolicyConfigured() {
        var forwarder = new RecordingForwarder();
        var pipeline = new Pipeline(
                new TenancyRouter(PipelineTestSupport.sharedIndexSpi(true)), forwarder, forwarder);

        var resp = pipeline.handle(adminRequest("/_cat/health"));
        assertThat(resp.status()).isEqualTo(400);
        assertThat(forwarder.lastPath).isNull();
    }

    @Test
    void allowedPrefixForwardsVerbatimToTheAdminCluster() {
        var forwarder = new RecordingForwarder();
        var pipeline = new Pipeline(
                new TenancyRouter(PipelineTestSupport.sharedIndexSpi(true)), forwarder, forwarder)
                .withAdminPolicy(AdminPolicy
                        .of(new ClusterId("ops"), List.of("/_cat/"))
                        .withEndpoint("http://ops:9200"));

        var resp = pipeline.handle(adminRequest("/_cat/health"));
        assertThat(resp.status()).isEqualTo(200);
        assertThat(forwarder.lastMethod).isEqualTo(HttpMethod.GET);
        assertThat(forwarder.lastPath).isEqualTo("/_cat/health");
        assertThat(forwarder.lastTarget.cluster()).isEqualTo(new ClusterId("ops"));
        assertThat(forwarder.lastTarget.endpointOverride()).contains("http://ops:9200");
    }

    @Test
    void aPathOutsideTheAllowListIsStillRefused() {
        var forwarder = new RecordingForwarder();
        var pipeline = new Pipeline(
                new TenancyRouter(PipelineTestSupport.sharedIndexSpi(true)), forwarder, forwarder)
                .withAdminPolicy(AdminPolicy.of(new ClusterId("ops"), List.of("/_cat/")));

        var resp = pipeline.handle(adminRequest("/_cluster/settings"));
        assertThat(resp.status()).isEqualTo(400);
        assertThat(forwarder.lastPath).isNull();
    }
}
