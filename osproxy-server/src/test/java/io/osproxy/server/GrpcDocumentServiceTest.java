package io.osproxy.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.MetadataUtils;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.grpc.GrpcRouting;
import io.osproxy.core.ClusterId;
import io.osproxy.core.IndexName;
import io.osproxy.engine.Pipeline;
import io.osproxy.server.grpc.pb.DocumentServiceGrpc;
import io.osproxy.server.grpc.pb.IndexReply;
import io.osproxy.server.grpc.pb.IndexRequest;
import io.osproxy.sink.MemorySink;
import io.osproxy.tenancy.TenancyRouter;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The gRPC ingress, over a real HTTP/2 connection to a real Helidon server:
 * the same auth/tenancy/isolation invariants as {@link ServerLoopbackTest}'s
 * REST path, driving the identical pipeline through a different front door.
 */
class GrpcDocumentServiceTest {

    private static WebServer server;
    private static ManagedChannel channel;

    @BeforeAll
    static void start() {
        MemorySink sink = new MemorySink();
        Pipeline pipeline = new Pipeline(
                new TenancyRouter(new ReferenceTenancy(
                        new ClusterId("primary"), new IndexName("shared"))),
                sink, sink);
        BearerAuth auth = new BearerAuth(Map.of(
                "secret-acme", "acme",
                "secret-globex", "globex"));
        AppHandler handler = new AppHandler(pipeline, auth);
        server = WebServer.builder()
                .port(0)
                .routing(handler::route)
                .addRouting(GrpcRouting.builder()
                        .intercept(new GrpcMetadataInterceptor())
                        .service(new GrpcDocumentService(handler, auth).descriptor()))
                .build()
                .start();
        channel = NettyChannelBuilder.forAddress("localhost", server.port())
                .usePlaintext()
                .build();
    }

    @AfterAll
    static void stop() {
        channel.shutdownNow();
        server.stop();
    }

    private static DocumentServiceGrpc.DocumentServiceBlockingStub stub(String token) {
        var stub = DocumentServiceGrpc.newBlockingStub(channel);
        if (token == null) {
            return stub;
        }
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + token);
        return stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    @Test
    void unauthenticatedIndexIsRefused() {
        IndexReply reply = stub(null).index(IndexRequest.newBuilder()
                .setIndex("orders")
                .setId("1")
                .setDocument(ByteString.copyFromUtf8("{}"))
                .build());
        assertThat(reply.getStatus()).isEqualTo(401);
    }

    @Test
    void indexRpcDrivesTheSamePipelineAsRest() {
        IndexReply put = stub("secret-acme").index(IndexRequest.newBuilder()
                .setIndex("orders")
                .setId("7")
                .setDocument(ByteString.copyFromUtf8("{\"msg\":\"hi\"}"))
                .build());
        assertThat(put.getStatus()).isEqualTo(201);
        assertThat(put.getBody().toStringUtf8())
                .contains("\"_id\":\"7\"")
                .contains("\"_index\":\"orders\"");
        assertThat(put.getRequestId()).isNotBlank();

        // The other tenant's token constructs a different physical id under
        // the same shared index, so it never collides with acme's document.
        IndexReply otherTenant = stub("secret-globex").index(IndexRequest.newBuilder()
                .setIndex("orders")
                .setId("7")
                .setDocument(ByteString.copyFromUtf8("{\"msg\":\"bye\"}"))
                .build());
        assertThat(otherTenant.getStatus()).isEqualTo(201);
    }

    @Test
    void autoIdIndexIsAPost() {
        IndexReply created = stub("secret-acme").index(IndexRequest.newBuilder()
                .setIndex("orders")
                .setDocument(ByteString.copyFromUtf8("{\"msg\":\"auto\"}"))
                .build());
        assertThat(created.getStatus()).isEqualTo(201);
        assertThat(created.getBody().toStringUtf8()).doesNotContain("\"_id\":\"\"");
    }
}
