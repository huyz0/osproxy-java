package io.osproxy.server;

import io.helidon.http.HeaderNames;
import io.helidon.http.Status;
import io.helidon.webserver.http.HttpRouting;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;
import io.osproxy.core.ErrorCode;
import io.osproxy.engine.Classify;
import io.osproxy.engine.Pipeline;
import io.osproxy.engine.PipelineResponse;
import io.osproxy.spi.Principal;
import io.osproxy.spi.RequestCtx;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Helidon ingress: authenticate → classify → pipeline → respond. Every
 * path funnels through the one handler; the pipeline decides what is
 * supported. Runs on virtual threads (Helidon SE 4's default), so the
 * blocking pipeline call is idiomatic.
 */
public final class AppHandler {

    private final Pipeline pipeline;
    private final BearerAuth auth;
    private final long maxBodyBytes;
    private final boolean requireTlsForMutation;

    public AppHandler(Pipeline pipeline, BearerAuth auth) {
        this(pipeline, auth, io.osproxy.config.ProxyConfig.DEFAULT_MAX_BODY_BYTES, false);
    }

    public AppHandler(
            Pipeline pipeline, BearerAuth auth, long maxBodyBytes, boolean requireTlsForMutation) {
        this.pipeline = pipeline;
        this.auth = auth;
        this.maxBodyBytes = maxBodyBytes;
        this.requireTlsForMutation = requireTlsForMutation;
    }

    /** Installs the catch-all route. */
    public void route(HttpRouting.Builder routing) {
        routing.any(this::handle);
    }

    private void handle(ServerRequest req, ServerResponse res) {
        Optional<RequestCtx.HttpMethod> method = method(req.prologue().method().text());
        if (method.isEmpty()) {
            send(res, PipelineResponse.error(ErrorCode.UNSUPPORTED_ENDPOINT));
            return;
        }

        Optional<Principal> principal = auth.authenticate(
                req.headers().first(HeaderNames.AUTHORIZATION),
                req.headers().first(HeaderNames.create("x-tenant")));
        if (principal.isEmpty()) {
            send(res, PipelineResponse.error(ErrorCode.AUTH_FAILED));
            return;
        }

        String path = req.path().rawPath();
        Classify.Classified classified = Classify.classify(method.get(), path);

        // NFR-S1: a body-mutating request must arrive over TLS when required.
        if (requireTlsForMutation && classified.endpoint().isWrite() && !req.isSecure()) {
            send(res, PipelineResponse.error(ErrorCode.UNAUTHORIZED));
            return;
        }

        // Bound the working set before buffering: over-cap bodies are refused
        // up front (fail-closed), not allocated for.
        long declared = req.headers()
                .first(HeaderNames.CONTENT_LENGTH)
                .map(Long::parseLong)
                .orElse(0L);
        if (declared > maxBodyBytes) {
            send(res, PipelineResponse.error(ErrorCode.PAYLOAD_TOO_LARGE));
            return;
        }
        byte[] body = req.content().hasEntity() ? req.content().as(byte[].class) : new byte[0];
        if (body.length > maxBodyBytes) {
            send(res, PipelineResponse.error(ErrorCode.PAYLOAD_TOO_LARGE));
            return;
        }

        List<Map.Entry<String, String>> headers = new ArrayList<>();
        req.headers().forEach(h -> headers.add(Map.entry(h.name(), h.values())));

        RequestCtx ctx = new RequestCtx(
                method.get(), path, classified.endpoint(),
                classified.logicalIndex(), classified.docId(),
                headers, body, principal.get(),
                req.query().rawValue());
        send(res, pipeline.handle(ctx));
    }

    private static void send(ServerResponse res, PipelineResponse out) {
        res.status(Status.create(out.status()))
                .header(HeaderNames.CONTENT_TYPE, "application/json")
                .send(out.body());
    }

    private static Optional<RequestCtx.HttpMethod> method(String name) {
        return switch (name) {
            case "GET" -> Optional.of(RequestCtx.HttpMethod.GET);
            case "PUT" -> Optional.of(RequestCtx.HttpMethod.PUT);
            case "POST" -> Optional.of(RequestCtx.HttpMethod.POST);
            case "DELETE" -> Optional.of(RequestCtx.HttpMethod.DELETE);
            case "HEAD" -> Optional.of(RequestCtx.HttpMethod.HEAD);
            default -> Optional.empty();
        };
    }
}
