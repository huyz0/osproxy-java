package io.osproxy.observe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.osproxy.core.EndpointKind;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DirectiveTest {

    private static final Directive.RequestAttrs ACME_SEARCH =
            new Directive.RequestAttrs("acme", Optional.of("orders"), EndpointKind.SEARCH);

    private static Directive directive(
            DiagLevel level, Optional<String> tenant, int perMille, long expiresAt) {
        return new Directive(
                "d1", level, tenant, Optional.empty(), Optional.empty(), perMille, false, expiresAt);
    }

    @Test
    void matchingIsByAttributesExpiryAndDeterministicSampling() {
        var d = directive(DiagLevel.VERBOSE, Optional.of("acme"), 1000, 100);
        assertThat(d.matches(ACME_SEARCH, "r1", 50)).isTrue();
        assertThat(d.matches(ACME_SEARCH, "r1", 100)).isFalse(); // expired
        var other = new Directive.RequestAttrs("globex", Optional.empty(), EndpointKind.SEARCH);
        assertThat(d.matches(other, "r1", 50)).isFalse();

        // Deterministic sampling: same request id, same verdict, every time.
        var sampled = directive(DiagLevel.VERBOSE, Optional.empty(), 500, 100);
        boolean first = sampled.matches(ACME_SEARCH, "some-request", 50);
        for (int i = 0; i < 10; i++) {
            assertThat(sampled.matches(ACME_SEARCH, "some-request", 50)).isEqualTo(first);
        }
        // 0 per-mille never matches; 1000 always does.
        assertThat(directive(DiagLevel.VERBOSE, Optional.empty(), 0, 100)
                .matches(ACME_SEARCH, "r", 50)).isFalse();
    }

    @Test
    void endpointAndIndexTargeting() {
        var byEndpoint = new Directive(
                "d", DiagLevel.OFF, Optional.empty(), Optional.empty(),
                Optional.of(EndpointKind.INGEST_BULK), 1000, false, 100);
        assertThat(byEndpoint.matches(ACME_SEARCH, "r", 50)).isFalse();
        var byIndex = new Directive(
                "d", DiagLevel.OFF, Optional.empty(), Optional.of("other"),
                Optional.empty(), 1000, false, 100);
        assertThat(byIndex.matches(ACME_SEARCH, "r", 50)).isFalse();
    }

    @Test
    void highestMatchingLevelWinsOverBaselineAndSilencers() {
        var set = new DirectiveSet(DiagLevel.SHAPE, List.of(
                directive(DiagLevel.OFF, Optional.of("acme"), 1000, 100),
                directive(DiagLevel.VERBOSE, Optional.of("acme"), 1000, 100)));
        assertThat(set.evaluate(ACME_SEARCH, "r", 50)).isEqualTo(DiagLevel.VERBOSE);

        // Only the silencer matches: OFF wins over the baseline.
        var silenced = new DirectiveSet(DiagLevel.SHAPE, List.of(
                directive(DiagLevel.OFF, Optional.of("acme"), 1000, 100)));
        assertThat(silenced.evaluate(ACME_SEARCH, "r", 50)).isEqualTo(DiagLevel.OFF);

        // Nothing matches: the baseline stands.
        assertThat(silenced.evaluate(
                        new Directive.RequestAttrs("globex", Optional.empty(), EndpointKind.SEARCH),
                        "r", 50))
                .isEqualTo(DiagLevel.SHAPE);
        // Everything expired: the baseline stands.
        assertThat(silenced.evaluate(ACME_SEARCH, "r", 200)).isEqualTo(DiagLevel.SHAPE);
    }

    @Test
    void storePublishReplacesTheSetAtomically() {
        var store = new DirectiveSet.InMemoryStore(DirectiveSet.baseline(DiagLevel.SHAPE));
        assertThat(store.load().directives()).isEmpty();
        store.publish(new DirectiveSet(DiagLevel.OFF, List.of(
                directive(DiagLevel.VERBOSE, Optional.empty(), 1000, 100))));
        assertThat(store.load().baseline()).isEqualTo(DiagLevel.OFF);
        assertThat(store.load().directives()).hasSize(1);
    }

    @Test
    void validationAndWireNames() {
        assertThatThrownBy(() -> directive(DiagLevel.OFF, Optional.empty(), 1001, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Directive(
                        "", DiagLevel.OFF, Optional.empty(), Optional.empty(),
                        Optional.empty(), 1, false, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(DiagLevel.fromWireName("verbose")).isEqualTo(DiagLevel.VERBOSE);
        assertThat(DiagLevel.fromWireName("nope")).isNull();
    }
}
