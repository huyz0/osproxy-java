package io.osproxy.engine;

import io.osproxy.core.ErrorCode;
import java.nio.charset.StandardCharsets;

/** What the pipeline hands the transport: status + JSON body. */
public record PipelineResponse(int status, byte[] body) {

    /** A shape-only error response for a wire code. */
    public static PipelineResponse error(ErrorCode code) {
        return new PipelineResponse(
                code.httpStatus(), code.toJsonBody().getBytes(StandardCharsets.UTF_8));
    }
}
