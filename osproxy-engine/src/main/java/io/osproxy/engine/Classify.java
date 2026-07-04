package io.osproxy.engine;

import io.osproxy.core.EndpointKind;
import io.osproxy.spi.RequestCtx;
import java.util.Optional;

/**
 * Classifies (method, path) into an {@link EndpointKind} plus the logical
 * index and doc id the path carries. Classification happens once at ingress;
 * nothing downstream re-parses the path. Unmatched paths are
 * {@link EndpointKind#UNKNOWN} and rejected fail-closed by the pipeline.
 */
public final class Classify {

    /** The classification result. */
    public record Classified(
            EndpointKind endpoint, Optional<String> logicalIndex, Optional<String> docId) {}

    private static final Classified UNKNOWN =
            new Classified(EndpointKind.UNKNOWN, Optional.empty(), Optional.empty());

    private Classify() {}

    /** Classifies a request line. The path must not include a query string. */
    public static Classified classify(RequestCtx.HttpMethod method, String path) {
        String[] segments = path.startsWith("/")
                ? path.substring(1).split("/")
                : path.split("/");
        if (segments.length == 0 || segments[0].isEmpty()) {
            return UNKNOWN;
        }

        // Index-less endpoints: /_bulk, /_mget, /_msearch, /_search, admin.
        if (segments[0].startsWith("_")) {
            return switch (segments[0]) {
                case "_bulk" -> only(method == RequestCtx.HttpMethod.POST,
                        EndpointKind.INGEST_BULK, null, null, segments, 1);
                case "_mget" -> only(isGetOrPost(method),
                        EndpointKind.MULTI_GET, null, null, segments, 1);
                case "_msearch" -> only(method == RequestCtx.HttpMethod.POST,
                        EndpointKind.MULTI_SEARCH, null, null, segments, 1);
                case "_search" -> classifySearchTail(method, null, segments, 1);
                case "_cat", "_cluster", "_nodes" ->
                        new Classified(EndpointKind.ADMIN, Optional.empty(), Optional.empty());
                default -> UNKNOWN;
            };
        }

        String index = segments[0];
        if (segments.length == 1) {
            return UNKNOWN;
        }
        return switch (segments[1]) {
            case "_doc" -> classifyDoc(method, index, segments);
            case "_create" -> segments.length == 3
                            && (method == RequestCtx.HttpMethod.PUT
                                    || method == RequestCtx.HttpMethod.POST)
                    ? new Classified(EndpointKind.INGEST_DOC,
                            Optional.of(index), Optional.of(segments[2]))
                    : UNKNOWN;
            case "_bulk" -> only(method == RequestCtx.HttpMethod.POST,
                    EndpointKind.INGEST_BULK, index, null, segments, 2);
            case "_search" -> classifySearchTail(method, index, segments, 2);
            case "_count" -> only(isGetOrPost(method),
                    EndpointKind.COUNT, index, null, segments, 2);
            case "_mget" -> only(isGetOrPost(method),
                    EndpointKind.MULTI_GET, index, null, segments, 2);
            case "_msearch" -> only(method == RequestCtx.HttpMethod.POST,
                    EndpointKind.MULTI_SEARCH, index, null, segments, 2);
            default -> UNKNOWN;
        };
    }

    private static Classified classifyDoc(
            RequestCtx.HttpMethod method, String index, String[] segments) {
        if (segments.length == 2) {
            // POST /{index}/_doc — auto-id ingest.
            return method == RequestCtx.HttpMethod.POST
                    ? new Classified(EndpointKind.INGEST_DOC, Optional.of(index), Optional.empty())
                    : UNKNOWN;
        }
        if (segments.length != 3) {
            return UNKNOWN;
        }
        String id = segments[2];
        return switch (method) {
            case PUT, POST -> new Classified(
                    EndpointKind.INGEST_DOC, Optional.of(index), Optional.of(id));
            case GET, HEAD -> new Classified(
                    EndpointKind.GET_BY_ID, Optional.of(index), Optional.of(id));
            case DELETE -> new Classified(
                    EndpointKind.DELETE_BY_ID, Optional.of(index), Optional.of(id));
        };
    }

    /** {@code _search} and its scroll tail ({@code _search/scroll} = cursor). */
    private static Classified classifySearchTail(
            RequestCtx.HttpMethod method, String index, String[] segments, int tailFrom) {
        if (!isGetOrPost(method)) {
            return UNKNOWN;
        }
        if (segments.length > tailFrom) {
            return segments[tailFrom].equals("scroll") || segments[tailFrom].equals("point_in_time")
                    ? new Classified(EndpointKind.CURSOR,
                            Optional.ofNullable(index), Optional.empty())
                    : UNKNOWN;
        }
        return new Classified(EndpointKind.SEARCH, Optional.ofNullable(index), Optional.empty());
    }

    private static boolean isGetOrPost(RequestCtx.HttpMethod method) {
        return method == RequestCtx.HttpMethod.GET || method == RequestCtx.HttpMethod.POST;
    }

    private static Classified only(
            boolean methodOk, EndpointKind kind, String index, String id,
            String[] segments, int expectedLen) {
        if (!methodOk || segments.length != expectedLen) {
            return UNKNOWN;
        }
        return new Classified(kind, Optional.ofNullable(index), Optional.ofNullable(id));
    }
}
