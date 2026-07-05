package io.osproxy.rewrite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class BulkAndMultiTest {

    @Test
    void bulkParsesAllVerbsWithPairing() throws Exception {
        String ndjson = """
                {"index":{"_index":"orders","_id":"1"}}
                {"msg":"a"}
                {"create":{"_id":"2"}}
                {"msg":"b"}
                {"update":{"_id":"2"}}
                {"doc":{"msg":"b2"}}
                {"delete":{"_id":"1"}}
                """;
        List<Bulk.Item> items = Bulk.parseBulk(ndjson.getBytes());
        assertThat(items).hasSize(4);
        assertThat(items.get(0).action()).isEqualTo(Bulk.Action.INDEX);
        assertThat(items.get(0).index()).contains("orders");
        assertThat(items.get(0).id()).contains("1");
        assertThat(items.get(0).doc()).isPresent();
        assertThat(items.get(3).action()).isEqualTo(Bulk.Action.DELETE);
        assertThat(items.get(3).doc()).isEmpty();
    }

    @Test
    void bulkRefusesMissingDocLineUnknownVerbAndBadJson() {
        assertThatKind("{\"index\":{}}\n", RewriteException.Kind.MALFORMED_MULTI);
        assertThatKind("{\"upsert\":{}}\n{}\n", RewriteException.Kind.MALFORMED_MULTI);
        assertThatKind("not json\n{}\n", RewriteException.Kind.INVALID_JSON);
        assertThatKind("{\"index\":{}}\nnot json\n", RewriteException.Kind.INVALID_JSON);
        assertThatKind("{\"index\":{},\"extra\":{}}\n{}\n", RewriteException.Kind.MALFORMED_MULTI);
        assertThatKind("{\"index\":\"notobj\"}\n{}\n", RewriteException.Kind.MALFORMED_MULTI);
        assertThatKind("{\"index\":{}}\n[1]\n", RewriteException.Kind.NOT_AN_OBJECT);
    }

    private static void assertThatKind(String ndjson, RewriteException.Kind kind) {
        assertThatThrownBy(() -> Bulk.parseBulk(ndjson.getBytes()))
                .isInstanceOf(RewriteException.class)
                .extracting(e -> ((RewriteException) e).kind())
                .isEqualTo(kind);
    }

    @Test
    void parseBulkStreamYieldsTheSameItemsAsTheBufferedParse() throws Exception {
        String ndjson = """
                {"index":{"_index":"orders","_id":"1"}}
                {"msg":"a"}
                {"create":{"_id":"2"}}
                {"msg":"b"}
                {"update":{"_id":"2"}}
                {"doc":{"msg":"b2"}}
                {"delete":{"_id":"1"}}
                """;
        var parser = Json.MAPPER.getFactory().createParser(ndjson.getBytes());
        var streamed = new java.util.ArrayList<Bulk.Item>();
        Bulk.parseBulkStream(parser).forEachRemaining(streamed::add);

        List<Bulk.Item> buffered = Bulk.parseBulk(ndjson.getBytes());
        assertThat(streamed).hasSize(buffered.size());
        for (int i = 0; i < buffered.size(); i++) {
            assertThat(streamed.get(i).action()).isEqualTo(buffered.get(i).action());
            assertThat(streamed.get(i).index()).isEqualTo(buffered.get(i).index());
            assertThat(streamed.get(i).id()).isEqualTo(buffered.get(i).id());
            assertThat(streamed.get(i).doc()).isEqualTo(buffered.get(i).doc());
        }
    }

    @Test
    void parseBulkStreamReportsAnEmptyBodyAsHasNextFalse() throws Exception {
        var parser = Json.MAPPER.getFactory().createParser("".getBytes());
        assertThat(Bulk.parseBulkStream(parser).hasNext()).isFalse();
    }

    @Test
    void parseBulkStreamWrapsAMalformedLineAsARewriteExceptionCause() throws Exception {
        var parser = Json.MAPPER.getFactory().createParser("not json\n{}\n".getBytes());
        var items = Bulk.parseBulkStream(parser);
        assertThatThrownBy(items::hasNext)
                .isInstanceOf(RuntimeException.class)
                .extracting(Throwable::getCause)
                .isInstanceOfSatisfying(RewriteException.class,
                        e -> assertThat(e.kind()).isEqualTo(RewriteException.Kind.INVALID_JSON));
    }

    @Test
    void mgetParsesDocsAndRefusesMissingIds() throws Exception {
        var items = Multi.parseMget(
                "{\"docs\":[{\"_index\":\"orders\",\"_id\":\"1\"},{\"_id\":\"2\"}]}".getBytes());
        assertThat(items).hasSize(2);
        assertThat(items.get(0).index()).contains("orders");
        assertThat(items.get(1).index()).isEmpty();

        assertThatThrownBy(() -> Multi.parseMget("{\"docs\":[]}".getBytes()))
                .isInstanceOf(RewriteException.class);
        assertThatThrownBy(() -> Multi.parseMget("{\"docs\":[{\"_index\":\"x\"}]}".getBytes()))
                .isInstanceOf(RewriteException.class);
        assertThatThrownBy(() -> Multi.parseMget("{}".getBytes()))
                .isInstanceOf(RewriteException.class);
    }

    @Test
    void msearchParsesHeaderBodyPairs() throws Exception {
        String ndjson = """
                {"index":"orders"}
                {"query":{"match_all":{}}}
                {}
                {"query":{"match":{"m":"x"}}}
                """;
        var items = Multi.parseMsearch(ndjson.getBytes());
        assertThat(items).hasSize(2);
        assertThat(items.get(0).index()).contains("orders");
        assertThat(items.get(1).index()).isEmpty();

        assertThatThrownBy(() -> Multi.parseMsearch("{\"index\":\"x\"}\n".getBytes()))
                .isInstanceOf(RewriteException.class);
        assertThatThrownBy(() -> Multi.parseMsearch("".getBytes()))
                .isInstanceOf(RewriteException.class);
        assertThatThrownBy(() -> Multi.parseMsearch("bad\n{}\n".getBytes()))
                .isInstanceOf(RewriteException.class);
    }
}
