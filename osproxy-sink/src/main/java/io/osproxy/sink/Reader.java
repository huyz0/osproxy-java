package io.osproxy.sink;

import io.osproxy.core.Target;
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
}
