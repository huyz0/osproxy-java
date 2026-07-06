package io.osproxy.server;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;

/**
 * Stashes each call's {@link Metadata} into an {@link io.grpc.Context} key so
 * a unary method body (which grpc-java hands only the request message and a
 * response observer, never the headers) can still read the bearer token, the
 * same way a REST handler reads {@code Authorization} off {@code
 * ServerRequest}. Installed once on the whole {@code GrpcRouting}.
 */
final class GrpcMetadataInterceptor implements ServerInterceptor {

    static final Context.Key<Metadata> METADATA = Context.key("osproxy-grpc-metadata");

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        Context ctx = Context.current().withValue(METADATA, headers);
        return Contexts.interceptCall(ctx, call, headers, next);
    }
}
