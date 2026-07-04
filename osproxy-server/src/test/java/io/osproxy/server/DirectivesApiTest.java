package io.osproxy.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.osproxy.core.EndpointKind;
import io.osproxy.core.ManualClock;
import io.osproxy.observe.DiagLevel;
import io.osproxy.observe.DirectiveSet;
import org.junit.jupiter.api.Test;

class DirectivesApiTest {

    private final ManualClock clock = new ManualClock();
    private final DirectivesApi api = new DirectivesApi(clock);

    @Test
    void decodesAFullPublishAndRoundTripsThroughIntrospection() throws Exception {
        String body = """
                {"baseline":"off","directives":[
                  {"id":"debug-acme","level":"verbose","tenant":"acme",
                   "index":"orders","endpoint":"search","principal":"user-1",
                   "sample_per_mille":250,"ring_buffer":true,"ttl_seconds":60}
                ]}
                """;
        DirectiveSet set = api.decode(body.getBytes());
        assertThat(set.baseline()).isEqualTo(DiagLevel.OFF);
        var d = set.directives().get(0);
        assertThat(d.tenant()).contains("acme");
        assertThat(d.endpoint()).contains(EndpointKind.SEARCH);
        assertThat(d.principal()).contains("user-1");
        assertThat(d.ringBuffer()).isTrue();
        assertThat(d.expiresAtNanos()).isEqualTo(60_000_000_000L);

        // Introspection re-emits the publishable shape; decoding it again
        // yields the same directive (the observe→act round trip).
        String introspected = api.introspect(set);
        DirectiveSet again = api.decode(introspected.getBytes());
        assertThat(again.baseline()).isEqualTo(DiagLevel.OFF);
        assertThat(again.directives().get(0).id()).isEqualTo("debug-acme");
        assertThat(again.directives().get(0).samplePerMille()).isEqualTo(250);
        assertThat(again.directives().get(0).ringBuffer()).isTrue();
        assertThat(again.directives().get(0).principal()).contains("user-1");
    }

    @Test
    void introspectionReportsRemainingTtlNeverWallTime() throws Exception {
        DirectiveSet set = api.decode(
                "{\"directives\":[{\"id\":\"d\",\"ttl_seconds\":100}]}".getBytes());
        clock.advanceNanos(40_000_000_000L);
        assertThat(api.introspect(set)).contains("\"ttl_seconds\":60");
        clock.advanceNanos(100_000_000_000L);
        assertThat(api.introspect(set)).contains("\"ttl_seconds\":0");
    }

    @Test
    void theDecoderFailsClosedOnAnythingUnknown() {
        assertRefused("not json");
        assertRefused("[1]");
        assertRefused("{\"basline\":\"shape\"}"); // misspelled top-level key
        assertRefused("{\"directives\":[{\"id\":\"d\",\"ttl_seconds\":60,\"tennant\":\"acme\"}]}");
        assertRefused("{\"directives\":[{\"id\":\"d\",\"ttl_seconds\":60,\"level\":\"loud\"}]}");
        assertRefused("{\"directives\":[{\"id\":\"d\",\"ttl_seconds\":60,\"endpoint\":\"nope\"}]}");
        assertRefused("{\"directives\":[{\"ttl_seconds\":60}]}"); // no id
        assertRefused("{\"directives\":[{\"id\":\"d\"}]}"); // no ttl
        assertRefused("{\"directives\":[{\"id\":\"d\",\"ttl_seconds\":0}]}");
        assertRefused("{\"directives\":[{\"id\":\"d\",\"ttl_seconds\":\"x\"}]}");
        assertRefused("{\"directives\":{\"id\":\"d\"}}"); // not an array
        assertRefused("{\"baseline\":\"shape\",\"directives\":[42]}");
    }

    private void assertRefused(String body) {
        assertThatThrownBy(() -> api.decode(body.getBytes()))
                .isInstanceOf(DirectivesApi.InvalidDirectives.class);
    }
}
