package io.osproxy.observe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.osproxy.core.EndpointKind;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ObserveTest {

    private static ExplainDoc doc(String id, int status) {
        return new ExplainDoc(
                id, "4bf92f3577b34da6a3ce929d0e0e4736", EndpointKind.SEARCH,
                "POST", status,
                status >= 400 ? Optional.of("upstream_failed") : Optional.empty(),
                1_500_000);
    }

    @Test
    void metricsBucketByStatusClass() {
        var metrics = new Metrics();
        metrics.record(201);
        metrics.record(200);
        metrics.record(404);
        metrics.record(502);
        assertThat(metrics.toJson()).isEqualTo(
                "{\"requests_total\":4,\"requests_ok\":2,"
                        + "\"requests_client_error\":1,\"requests_upstream_error\":1}");
    }

    @Test
    void explainDocJsonIsShapeOnlyAndStable() {
        assertThat(doc("req-1", 200).toJson()).isEqualTo(
                "{\"request_id\":\"req-1\","
                        + "\"trace_id\":\"4bf92f3577b34da6a3ce929d0e0e4736\","
                        + "\"endpoint\":\"search\",\"method\":\"POST\",\"status\":200,"
                        + "\"duration_nanos\":1500000}");
        assertThat(doc("req-2", 502).toJson()).contains("\"error\":\"upstream_failed\"");
    }

    @Test
    void explainStoreIsABoundedRingWithLookup() {
        var store = new ExplainStore(2);
        store.record(doc("a", 200));
        store.record(doc("b", 200));
        store.record(doc("c", 200)); // evicts a
        assertThat(store.lookup("a")).isEmpty();
        assertThat(store.lookup("b")).isPresent();
        assertThat(store.lookup("c")).isPresent();
        assertThat(store.toJsonArray()).startsWith("[{\"request_id\":\"b\"");

        // Re-recording an id does not grow the ring.
        store.record(doc("c", 404));
        assertThat(store.lookup("b")).isPresent();
        assertThat(store.lookup("c").orElseThrow().status()).isEqualTo(404);

        assertThatThrownBy(() -> new ExplainStore(0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
