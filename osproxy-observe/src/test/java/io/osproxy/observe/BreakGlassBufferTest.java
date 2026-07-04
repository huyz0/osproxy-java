package io.osproxy.observe;

import static org.assertj.core.api.Assertions.assertThat;

import io.osproxy.core.EndpointKind;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BreakGlassBufferTest {

    @Test
    void capturesInOrderAndEvictsOldestWhenFull() {
        var tape = new BreakGlassBuffer(2);
        assertThat(tape.size()).isZero();
        assertThat(tape.snapshot()).isEmpty();

        tape.capture("{\"request_id\":\"r1\"}");
        tape.capture("{\"request_id\":\"r2\"}");
        tape.capture("{\"request_id\":\"r3\"}"); // evicts r1

        List<String> snapshot = tape.snapshot();
        assertThat(snapshot).hasSize(2);
        assertThat(snapshot.get(0)).contains("r2");
        assertThat(snapshot.get(1)).contains("r3");
        assertThat(tape.size()).isEqualTo(2);
    }

    @Test
    void capacityIsAtLeastOne() {
        var tape = new BreakGlassBuffer(0);
        tape.capture("a");
        tape.capture("b");
        assertThat(tape.snapshot()).containsExactly("b");
    }

    @Test
    void directiveSetReportsWhetherAnyMatchingDirectiveWantsRingBuffer() {
        var attrs = new Directive.RequestAttrs(
                "acme", Optional.of("orders"), EndpointKind.SEARCH, "user-1");
        var withRingBuffer = new Directive(
                "d1", DiagLevel.SHAPE, Optional.of("acme"), Optional.empty(), Optional.empty(),
                Optional.empty(), 1000, true, 100);
        var withoutRingBuffer = new Directive(
                "d2", DiagLevel.SHAPE, Optional.of("acme"), Optional.empty(), Optional.empty(),
                Optional.empty(), 1000, false, 100);

        var set = new DirectiveSet(DiagLevel.OFF, List.of(withoutRingBuffer, withRingBuffer));
        assertThat(set.wantsRingBuffer(attrs, "r", 50)).isTrue();

        var noneWant = new DirectiveSet(DiagLevel.OFF, List.of(withoutRingBuffer));
        assertThat(noneWant.wantsRingBuffer(attrs, "r", 50)).isFalse();

        // Expired directive never wants it, even if flagged.
        assertThat(set.wantsRingBuffer(attrs, "r", 200)).isFalse();

        // No matching directives at all.
        assertThat(DirectiveSet.baseline(DiagLevel.OFF).wantsRingBuffer(attrs, "r", 50)).isFalse();
    }
}
