package io.osproxy.sink;

import io.osproxy.core.Target;
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

    private static SinkException cursorsUnsupported() {
        return new SinkException(
                io.osproxy.core.ErrorCode.UNSUPPORTED_ENDPOINT,
                "this reader does not support cursors");
    }
}
