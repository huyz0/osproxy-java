package io.osproxy.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.osproxy.core.EndpointKind;
import io.osproxy.spi.RequestCtx.HttpMethod;
import org.junit.jupiter.api.Test;

class ClassifyTest {

    private static Classify.Classified c(HttpMethod m, String path) {
        return Classify.classify(m, path);
    }

    @Test
    void docCrudClassification() {
        assertThat(c(HttpMethod.PUT, "/orders/_doc/7").endpoint())
                .isEqualTo(EndpointKind.INGEST_DOC);
        assertThat(c(HttpMethod.POST, "/orders/_doc").endpoint())
                .isEqualTo(EndpointKind.INGEST_DOC);
        assertThat(c(HttpMethod.POST, "/orders/_doc").docId()).isEmpty();
        assertThat(c(HttpMethod.PUT, "/orders/_create/7").endpoint())
                .isEqualTo(EndpointKind.INGEST_DOC);
        assertThat(c(HttpMethod.GET, "/orders/_doc/7").endpoint())
                .isEqualTo(EndpointKind.GET_BY_ID);
        assertThat(c(HttpMethod.HEAD, "/orders/_doc/7").endpoint())
                .isEqualTo(EndpointKind.GET_BY_ID);
        assertThat(c(HttpMethod.DELETE, "/orders/_doc/7").endpoint())
                .isEqualTo(EndpointKind.DELETE_BY_ID);
        assertThat(c(HttpMethod.GET, "/orders/_doc/7").logicalIndex()).contains("orders");
        assertThat(c(HttpMethod.GET, "/orders/_doc/7").docId()).contains("7");
    }

    @Test
    void searchCountBulkAndMultis() {
        assertThat(c(HttpMethod.GET, "/orders/_search").endpoint()).isEqualTo(EndpointKind.SEARCH);
        assertThat(c(HttpMethod.POST, "/_search").endpoint()).isEqualTo(EndpointKind.SEARCH);
        assertThat(c(HttpMethod.POST, "/_search").logicalIndex()).isEmpty();
        assertThat(c(HttpMethod.POST, "/orders/_count").endpoint()).isEqualTo(EndpointKind.COUNT);
        assertThat(c(HttpMethod.POST, "/_bulk").endpoint()).isEqualTo(EndpointKind.INGEST_BULK);
        assertThat(c(HttpMethod.POST, "/orders/_bulk").endpoint())
                .isEqualTo(EndpointKind.INGEST_BULK);
        assertThat(c(HttpMethod.POST, "/_mget").endpoint()).isEqualTo(EndpointKind.MULTI_GET);
        assertThat(c(HttpMethod.GET, "/orders/_mget").endpoint()).isEqualTo(EndpointKind.MULTI_GET);
        assertThat(c(HttpMethod.POST, "/_msearch").endpoint())
                .isEqualTo(EndpointKind.MULTI_SEARCH);
        assertThat(c(HttpMethod.POST, "/orders/_msearch").endpoint())
                .isEqualTo(EndpointKind.MULTI_SEARCH);
    }

    @Test
    void deleteByQueryIsPostOnly() {
        assertThat(c(HttpMethod.POST, "/orders/_delete_by_query").endpoint())
                .isEqualTo(EndpointKind.DELETE_BY_QUERY);
        assertThat(c(HttpMethod.POST, "/orders/_delete_by_query").logicalIndex())
                .contains("orders");
        assertThat(c(HttpMethod.GET, "/orders/_delete_by_query").endpoint())
                .isEqualTo(EndpointKind.UNKNOWN);
        // No index-less form (delete_by_query always needs a target index).
        assertThat(c(HttpMethod.POST, "/_delete_by_query").endpoint())
                .isEqualTo(EndpointKind.UNKNOWN);
    }

    @Test
    void cursorAdminAndUnknown() {
        assertThat(c(HttpMethod.POST, "/_search/scroll").endpoint()).isEqualTo(EndpointKind.CURSOR);
        assertThat(c(HttpMethod.POST, "/orders/_search/scroll").endpoint())
                .isEqualTo(EndpointKind.CURSOR);
        assertThat(c(HttpMethod.POST, "/_search/point_in_time").endpoint())
                .isEqualTo(EndpointKind.CURSOR);
        assertThat(c(HttpMethod.GET, "/_cat/indices").endpoint()).isEqualTo(EndpointKind.ADMIN);
        assertThat(c(HttpMethod.GET, "/_cluster/health").endpoint()).isEqualTo(EndpointKind.ADMIN);

        assertThat(c(HttpMethod.GET, "/").endpoint()).isEqualTo(EndpointKind.UNKNOWN);
        assertThat(c(HttpMethod.GET, "/orders").endpoint()).isEqualTo(EndpointKind.UNKNOWN);
        assertThat(c(HttpMethod.PUT, "/_bulk").endpoint()).isEqualTo(EndpointKind.UNKNOWN);
        assertThat(c(HttpMethod.DELETE, "/orders/_search").endpoint())
                .isEqualTo(EndpointKind.UNKNOWN);
        assertThat(c(HttpMethod.GET, "/orders/_doc").endpoint()).isEqualTo(EndpointKind.UNKNOWN);
        assertThat(c(HttpMethod.GET, "/orders/_doc/7/extra").endpoint())
                .isEqualTo(EndpointKind.UNKNOWN);
        assertThat(c(HttpMethod.GET, "/_unknown").endpoint()).isEqualTo(EndpointKind.UNKNOWN);
        assertThat(c(HttpMethod.GET, "/orders/_update_by_query").endpoint())
                .isEqualTo(EndpointKind.UNKNOWN);
        assertThat(c(HttpMethod.POST, "/orders/_search/other").endpoint())
                .isEqualTo(EndpointKind.UNKNOWN);
        assertThat(c(HttpMethod.GET, "/orders/_create/7").endpoint())
                .isEqualTo(EndpointKind.UNKNOWN);
    }
}
