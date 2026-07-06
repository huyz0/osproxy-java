package io.osproxy.server;

import com.google.protobuf.ByteString;
import io.grpc.Metadata;
import io.grpc.stub.StreamObserver;
import io.helidon.webserver.grpc.GrpcServiceDescriptor;
import io.osproxy.core.ErrorCode;
import io.osproxy.engine.Classify;
import io.osproxy.server.grpc.pb.IndexReply;
import io.osproxy.server.grpc.pb.IndexRequest;
import io.osproxy.server.grpc.pb.Osproxy;
import io.osproxy.spi.Principal;
import io.osproxy.spi.RequestCtx;
import java.util.List;
import java.util.Optional;

/**
 * The gRPC ingress: a thin, protocol-specific front door mirroring the Rust
 * sibling project's {@code DocumentService}. Every RPC is adapted into the
 * same {@link RequestCtx} the REST path builds and driven through the
 * identical {@link AppHandler#dispatch}, so tenancy, isolation, and
 * observability are unchanged across protocols; only the wire envelope
 * differs. Registered on the same {@code GrpcRouting} as the REST {@code
 * HttpRouting}, so it shares the WebServer's port and TLS configuration
 * rather than the Rust project's separate {@code grpc_bind} listener.
 *
 * <p>Not a {@link io.helidon.webserver.grpc.GrpcService}: that interface's
 * {@code builder(GrpcService)} shortcut never calls {@code update()} or
 * {@code proto()} on its own, so it produces an empty, path-less service
 * registration. {@link #descriptor()} builds the {@link GrpcServiceDescriptor}
 * directly instead, which is also the only registration path a routing-level
 * {@code GrpcRouting.Builder#intercept} actually reaches (verified against
 * Helidon 4.2.2's bytecode: the routing-level interceptor bag is merged into
 * every descriptor registered via {@code .service(GrpcServiceDescriptor)} at
 * build time, but a plain {@code .service(GrpcService)} never sees it).
 */
public final class GrpcDocumentService {

    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> TRACEPARENT =
            Metadata.Key.of("traceparent", Metadata.ASCII_STRING_MARSHALLER);

    private final AppHandler handler;
    private final BearerAuth auth;

    public GrpcDocumentService(AppHandler handler, BearerAuth auth) {
        this.handler = handler;
        this.auth = auth;
    }

    /** The registration Helidon's {@code GrpcRouting} understands. */
    public GrpcServiceDescriptor descriptor() {
        return GrpcServiceDescriptor.builder(GrpcDocumentService.class, "DocumentService")
                .proto(Osproxy.getDescriptor())
                .unary("Index", this::index)
                .build();
    }

    private void index(IndexRequest request, StreamObserver<IndexReply> observer) {
        Metadata metadata = GrpcMetadataInterceptor.METADATA.get();
        Optional<String> authorization = Optional.ofNullable(metadata)
                .map(m -> m.get(AUTHORIZATION));
        Optional<Principal> principal = auth.authenticate(authorization, Optional.empty());
        if (principal.isEmpty()) {
            observer.onNext(errorReply(ErrorCode.AUTH_FAILED));
            observer.onCompleted();
            return;
        }

        // Synthesizes the REST request this RPC stands for, then classifies
        // it the same way, so endpoint/index/doc-id come from one code path.
        boolean autoId = request.getId().isEmpty();
        RequestCtx.HttpMethod method = autoId ? RequestCtx.HttpMethod.POST : RequestCtx.HttpMethod.PUT;
        String path = autoId
                ? "/" + request.getIndex() + "/_doc"
                : "/" + request.getIndex() + "/_doc/" + request.getId();
        Classify.Classified classified = Classify.classify(method, path);
        RequestCtx ctx = new RequestCtx(
                method, path, classified.endpoint(), classified.logicalIndex(), classified.docId(),
                List.of(), request.getDocument().toByteArray(), principal.get());

        Optional<String> traceparent = Optional.ofNullable(metadata).map(m -> m.get(TRACEPARENT));
        AppHandler.Dispatched dispatched = handler.traced(
                List.of(), traceparent, () -> handler.dispatch(ctx));

        observer.onNext(IndexReply.newBuilder()
                .setStatus(dispatched.response().status())
                .setBody(ByteString.copyFrom(dispatched.response().body()))
                .setRequestId(dispatched.requestId())
                .build());
        observer.onCompleted();
    }

    private static IndexReply errorReply(ErrorCode code) {
        return IndexReply.newBuilder()
                .setStatus(code.httpStatus())
                .setBody(ByteString.copyFromUtf8(code.toJsonBody()))
                .build();
    }
}
