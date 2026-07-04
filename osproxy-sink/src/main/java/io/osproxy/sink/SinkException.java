package io.osproxy.sink;

import io.osproxy.core.ErrorCode;

/**
 * The sink failed to reach or be understood by the upstream. Carries the
 * wire code the engine serves — upstream trouble is {@code upstream_failed}
 * (502) or {@code overloaded} (429), never an opaque 500.
 */
public final class SinkException extends Exception {

    private final ErrorCode code;

    public SinkException(ErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public SinkException(ErrorCode code, String message) {
        this(code, message, null);
    }

    public ErrorCode errorCode() {
        return code;
    }
}
