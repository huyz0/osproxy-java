package io.osproxy.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.osproxy.engine.CursorCodec;
import org.junit.jupiter.api.Test;

class HmacCursorCodecTest {

    private static final HmacCursorCodec CODEC =
            new HmacCursorCodec("0123456789abcdef-test-key");

    @Test
    void encodeDecodeRoundTrips() {
        String wire = CODEC.encode("eu-1", "DXF1ZXJ5QW5kRmV0Y2g=");
        CursorCodec.Decoded decoded = CODEC.decode(wire).orElseThrow();
        assertThat(decoded.cluster()).isEqualTo("eu-1");
        assertThat(decoded.upstreamId()).isEqualTo("DXF1ZXJ5QW5kRmV0Y2g=");
    }

    @Test
    void forgedOrMangledEnvelopesDecodeToEmpty() {
        String wire = CODEC.encode("eu-1", "scroll-1");
        // Flip the payload to point at another cluster: MAC no longer matches.
        String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("us-1|scroll-1".getBytes());
        String forged = forgedPayload + wire.substring(wire.indexOf('.'));
        assertThat(CODEC.decode(forged)).isEmpty();

        assertThat(CODEC.decode("no-dot")).isEmpty();
        assertThat(CODEC.decode(".leading")).isEmpty();
        assertThat(CODEC.decode("trailing.")).isEmpty();
        assertThat(CODEC.decode("!!bad-base64!!.also")).isEmpty();
        // A different key's envelope does not verify.
        String other = new HmacCursorCodec("another-key-0123456789").encode("eu-1", "s");
        assertThat(CODEC.decode(other)).isEmpty();
    }

    @Test
    void idsWithSeparatorsSurvive() {
        String wire = CODEC.encode("c1", "id|with|pipes==");
        assertThat(CODEC.decode(wire).orElseThrow().upstreamId()).isEqualTo("id|with|pipes==");
    }

    @Test
    void weakKeysAreRefused() {
        assertThatThrownBy(() -> new HmacCursorCodec("short"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
