package io.osproxy.rewrite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DocIdsTest {

    @Test
    void constructIdExpandsPartitionAndBodyPaths() throws Exception {
        var doc = Json.parseObject("{\"order_id\":1001}".getBytes());
        assertThat(DocIds.constructId("{partition}:{body.order_id}", "acme", doc))
                .isEqualTo("acme:1001");
    }

    @Test
    void constructIdRefusesUnknownPlaceholdersAndNonScalars() throws Exception {
        var doc = Json.parseObject("{\"nested\":{\"a\":1}}".getBytes());
        assertThatThrownBy(() -> DocIds.constructId("{nope}", "p", doc))
                .isInstanceOf(RewriteException.class)
                .extracting(e -> ((RewriteException) e).kind())
                .isEqualTo(RewriteException.Kind.UNSUPPORTED_PLACEHOLDER);
        assertThatThrownBy(() -> DocIds.constructId("{body.nested}", "p", doc))
                .isInstanceOf(RewriteException.class)
                .extracting(e -> ((RewriteException) e).kind())
                .isEqualTo(RewriteException.Kind.PATH_NOT_SCALAR);
        assertThatThrownBy(() -> DocIds.constructId("{body.absent}", "p", doc))
                .isInstanceOf(RewriteException.class);
    }

    @Test
    void logicalToPhysicalFramesTheId() throws Exception {
        assertThat(DocIds.mapLogicalToPhysical("{partition}:{id}", "acme", "7"))
                .isEqualTo("acme:7");
        assertThat(DocIds.mapLogicalToPhysical("{partition}:{body.k}", "acme", "7"))
                .isEqualTo("acme:7");
    }

    @Test
    void physicalToLogicalStripsTheFrameOrRefuses() throws Exception {
        assertThat(DocIds.mapPhysicalToLogical("{partition}:{id}", "acme", "acme:7"))
                .contains("7");
        // Another partition's id does not fit the frame.
        assertThat(DocIds.mapPhysicalToLogical("{partition}:{id}", "acme", "globex:7"))
                .isEmpty();
        // Physical id shorter than the frame.
        assertThat(DocIds.mapPhysicalToLogical("{partition}:{id}-x", "acme", "acme:"))
                .isEmpty();
    }

    @Test
    void irreversibleTemplatesAreRefused() {
        assertThatThrownBy(() -> DocIds.mapLogicalToPhysical("{id}:{body.k}", "p", "7"))
                .isInstanceOf(RewriteException.class)
                .extracting(e -> ((RewriteException) e).kind())
                .isEqualTo(RewriteException.Kind.IRREVERSIBLE_ID_TEMPLATE);
        assertThatThrownBy(() -> DocIds.mapLogicalToPhysical("{partition}", "p", "7"))
                .isInstanceOf(RewriteException.class)
                .extracting(e -> ((RewriteException) e).kind())
                .isEqualTo(RewriteException.Kind.IRREVERSIBLE_ID_TEMPLATE);
        assertThatThrownBy(() -> DocIds.mapLogicalToPhysical("{unclosed", "p", "7"))
                .isInstanceOf(RewriteException.class);
    }
}
