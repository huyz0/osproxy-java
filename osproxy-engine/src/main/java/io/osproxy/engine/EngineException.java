package io.osproxy.engine;

import io.osproxy.core.ErrorCode;

/**
 * An engine-level refusal that is neither an SPI nor a sink failure — e.g.
 * a stale-epoch write refused mid-bulk. Carries the wire code directly.
 */
final class EngineException extends Exception {

    private final ErrorCode code;

    EngineException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    ErrorCode errorCode() {
        return code;
    }
}
