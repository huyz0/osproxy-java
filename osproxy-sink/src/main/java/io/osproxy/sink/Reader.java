package io.osproxy.sink;

import io.osproxy.core.Target;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The read-path seam: fetch and query the physical store. */
public interface Reader {

    /** A raw upstream response: status + JSON body bytes. */
    record Response(int status, byte[] body) {
        /** Whether the upstream answered 2xx. */
        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    /** {@code GET /{index}/_doc/{physicalId}}. */
    Response get(Target target, String physicalId, Optional<String> routing) throws SinkException;

    /** {@code POST /{index}/_search} with the (already wrapped) body. */
    Response search(Target target, byte[] body) throws SinkException;

    /** {@code POST /{index}/_count} with the (already wrapped) body. */
    Response count(Target target, byte[] body) throws SinkException;

    /**
     * Streaming twin of {@link #search}: {@code body} is piped straight into
     * the upstream request rather than passed as a materialized byte[] —
     * the request-side counterpart of {@link #search}'s buffered form for a
     * body the engine wrapped via {@code Queries.wrapQueryStreaming}. The
     * response is still returned buffered (result shaping needs the whole
     * tree). Default: unsupported, so a reader that hasn't wired streaming
     * search fails closed.
     */
    default Response searchStreaming(Target target, InputStream body) throws SinkException {
        throw new SinkException(
                io.osproxy.core.ErrorCode.UNSUPPORTED_ENDPOINT,
                "this reader does not support streaming search");
    }

    /** Streaming twin of {@link #count}, mirroring {@link #searchStreaming}. */
    default Response countStreaming(Target target, InputStream body) throws SinkException {
        throw new SinkException(
                io.osproxy.core.ErrorCode.UNSUPPORTED_ENDPOINT,
                "this reader does not support streaming count");
    }

    // ---- cursor lifecycle (scroll + PIT) ----
    // Default implementations refuse: a reader that has not wired cursors
    // fails closed rather than mis-serving them.

    /** {@code POST /{index}/_search?scroll=<ttl>} — opens a scroll. */
    default Response searchScroll(Target target, byte[] body, String scrollTtl)
            throws SinkException {
        throw cursorsUnsupported();
    }

    /** {@code POST /_search/scroll} — the next batch. */
    default Response scrollNext(Target target, byte[] body) throws SinkException {
        throw cursorsUnsupported();
    }

    /** {@code DELETE /_search/scroll} — releases a scroll. */
    default Response scrollDelete(Target target, byte[] body) throws SinkException {
        throw cursorsUnsupported();
    }

    /** {@code POST /{index}/_search/point_in_time?keep_alive=<ttl>}. */
    default Response pitOpen(Target target, String keepAlive) throws SinkException {
        throw cursorsUnsupported();
    }

    /** {@code DELETE /_search/point_in_time}. */
    default Response pitClose(Target target, byte[] body) throws SinkException {
        throw cursorsUnsupported();
    }

    /** {@code POST /_search} — index-less search (a PIT search names no index). */
    default Response searchIndexless(Target target, byte[] body) throws SinkException {
        throw cursorsUnsupported();
    }

    /**
     * Forwards a request verbatim: method, path, query, and body go upstream
     * as-is, carrying {@code extraHeaders} (the tenant-agnostic passthrough
     * primitive). Default implementations refuse: a reader that has not
     * wired verbatim forwarding fails closed.
     */
    default Response forward(
            Target target, io.osproxy.spi.RequestCtx.HttpMethod method, String path,
            String query, byte[] body, List<Map.Entry<String, String>> extraHeaders)
            throws SinkException {
        throw new SinkException(
                io.osproxy.core.ErrorCode.UNSUPPORTED_ENDPOINT,
                "this reader does not support verbatim forwarding");
    }

    /**
     * A live upstream response for {@link #forwardStreaming}: the status and
     * an open {@link InputStream} over the response body, never buffered.
     * {@link #close} releases the underlying connection; callers must close
     * it (try-with-resources) once they are done reading {@link #body}.
     */
    record StreamedResponse(int status, InputStream body, AutoCloseable connection)
            implements AutoCloseable {
        @Override
        public void close() {
            try {
                connection.close();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new java.io.UncheckedIOException(
                        "closing the upstream connection", new java.io.IOException(e));
            }
        }
    }

    /**
     * The streaming twin of {@link #forward}: method, path, query, and
     * headers go upstream as-is, but the request body is piped straight
     * from {@code requestBody} and the response body is handed back live,
     * so neither direction is ever materialized as a byte array — the
     * primitive a tenant-agnostic passthrough proxy needs to relay a body
     * of any size without a memory cap. Default implementations refuse: a
     * reader that has not wired streaming forwarding fails closed.
     */
    default StreamedResponse forwardStreaming(
            Target target, io.osproxy.spi.RequestCtx.HttpMethod method, String path,
            String query, InputStream requestBody, List<Map.Entry<String, String>> extraHeaders)
            throws SinkException {
        throw new SinkException(
                io.osproxy.core.ErrorCode.UNSUPPORTED_ENDPOINT,
                "this reader does not support streaming verbatim forwarding");
    }

    private static SinkException cursorsUnsupported() {
        return new SinkException(
                io.osproxy.core.ErrorCode.UNSUPPORTED_ENDPOINT,
                "this reader does not support cursors");
    }
}
