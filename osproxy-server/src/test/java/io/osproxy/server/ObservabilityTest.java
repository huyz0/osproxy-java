package io.osproxy.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.osproxy.core.EndpointKind;
import io.osproxy.core.ManualClock;
import io.osproxy.observe.DiagLevel;
import io.osproxy.observe.Directive;
import io.osproxy.observe.DiagnosticSink;
import io.osproxy.observe.DirectiveSet;
import io.osproxy.observe.ExplainDoc;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ObservabilityTest {

    private static final Directive.RequestAttrs ATTRS =
            new Directive.RequestAttrs("acme", Optional.empty(), EndpointKind.INGEST_DOC, "user-1");

    private static ExplainDoc doc(String requestId) {
        return new ExplainDoc(requestId, "trace-1", EndpointKind.INGEST_DOC, "PUT", 201,
                Optional.empty(), 1000);
    }

    private static DirectiveSet ringBufferDirective() {
        return new DirectiveSet(DiagLevel.SHAPE, List.of(new Directive(
                "d1", DiagLevel.SHAPE, Optional.of("acme"), Optional.empty(), Optional.empty(),
                Optional.empty(), 1000, true, Long.MAX_VALUE)));
    }

    @Test
    void aThrowingDiagnosticSinkNeverBreaksRecording() {
        DiagnosticSink throwing = new DiagnosticSink() {
            @Override
            public void emit(String docJson) {
                throw new RuntimeException("aggregator unreachable");
            }
        };
        var observability = new Observability(
                16, Optional.empty(),
                new DirectiveSet.InMemoryStore(ringBufferDirective()), new ManualClock())
                .withDiagnosticSink(throwing);

        assertThatCode(() -> observability.record(doc("r1"), ATTRS)).doesNotThrowAnyException();
        // The local break-glass ring still captured it: the sink's failure
        // does not prevent the always-available local tape from working.
        assertThat(observability.breakGlass().snapshot()).hasSize(1);
        assertThat(observability.metrics().toJson()).contains("\"requests_total\":1");
    }

    @Test
    void aDisabledDiagnosticSinkIsNeverInvoked() {
        var invoked = new boolean[1];
        DiagnosticSink neverCalled = new DiagnosticSink() {
            @Override
            public boolean enabled() {
                return false;
            }

            @Override
            public void emit(String docJson) {
                invoked[0] = true;
            }
        };
        var observability = new Observability(
                16, Optional.empty(),
                new DirectiveSet.InMemoryStore(ringBufferDirective()), new ManualClock())
                .withDiagnosticSink(neverCalled);

        observability.record(doc("r1"), ATTRS);
        assertThat(invoked[0]).isFalse();
        assertThat(observability.breakGlass().snapshot()).hasSize(1);
    }
}
