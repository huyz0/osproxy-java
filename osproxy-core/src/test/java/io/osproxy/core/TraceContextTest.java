package io.osproxy.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TraceContextTest {

    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String SPAN = "00f067aa0ba902b7";

    @Test
    void parsesAndReformatsTraceparent() {
        var tc = TraceContext.parse("00-" + TRACE + "-" + SPAN + "-01").orElseThrow();
        assertThat(tc.traceId()).isEqualTo(TRACE);
        assertThat(tc.sampled()).isTrue();
        assertThat(tc.toTraceparent()).isEqualTo("00-" + TRACE + "-" + SPAN + "-01");
        assertThat(TraceContext.parse("00-" + TRACE + "-" + SPAN + "-00")
                .orElseThrow().sampled()).isFalse();
    }

    @Test
    void malformedTraceparentsAreEmptyNotErrors() {
        assertThat(TraceContext.parse(null)).isEmpty();
        assertThat(TraceContext.parse("")).isEmpty();
        // Future versions parse leniently (W3C); the invalid ff does not.
        assertThat(TraceContext.parse("01-" + TRACE + "-" + SPAN + "-01")).isPresent();
        assertThat(TraceContext.parse("ff-" + TRACE + "-" + SPAN + "-01")).isEmpty();
        assertThat(TraceContext.parse("0x-" + TRACE + "-" + SPAN + "-01")).isEmpty();
        assertThat(TraceContext.parse("00-short-" + SPAN + "-01")).isEmpty();
        assertThat(TraceContext.parse("00-" + "0".repeat(32) + "-" + SPAN + "-01")).isEmpty();
        assertThat(TraceContext.parse("00-" + TRACE + "-" + SPAN + "-zz")).isEmpty();
    }

    @Test
    void mintAndChildKeepTheChain() {
        byte[] sixteen = new byte[16];
        byte[] eight = new byte[8];
        eight[7] = 5;
        sixteen[15] = 9;
        var minted = TraceContext.mint(sixteen, eight);
        assertThat(minted.sampled()).isTrue();
        var child = minted.child(new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
        assertThat(child.traceId()).isEqualTo(minted.traceId());
        assertThat(child.spanId()).isNotEqualTo(minted.spanId());
    }

    @Test
    void allZeroRandomnessStillYieldsValidIds() {
        var tc = TraceContext.mint(new byte[16], new byte[8]);
        assertThat(tc.traceId()).endsWith("1");
        assertThat(tc.spanId()).endsWith("1");
    }

    @Test
    void invalidIdsAreRefused() {
        assertThatThrownBy(() -> new TraceContext("nope", SPAN, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TraceContext.mint(new byte[3], new byte[8]))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
