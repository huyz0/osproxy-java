package io.osproxy.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.helidon.webserver.WebServer;
import io.osproxy.config.ProxyConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Async write mode against a real broker: the 202 is only sent after the
 * acked produce, and the envelope really lands on the topic, fully
 * transformed. Tagged {@code integration} (needs Docker).
 */
@Tag("integration")
class KafkaFanoutE2eTest {

    private static KafkaContainer kafka;
    private static WebServer proxy;
    private static HttpClient client;
    private static String base;

    @BeforeAll
    static void start() {
        kafka = new KafkaContainer("apache/kafka:3.7.0");
        kafka.start();

        // The upstream URL is never dialed for an async write.
        proxy = Main.start(new ProxyConfig(
                0, "http://localhost:59200", "shared", Map.of(),
                ProxyConfig.DEFAULT_MAX_BODY_BYTES, false,
                Optional.empty(), Optional.empty(), false, Optional.empty(),
                false, Optional.empty(), "osproxy",
                Optional.empty(), 10,
                Optional.of(kafka.getBootstrapServers()), "osproxy-writes"));
        base = "http://localhost:" + proxy.port();
        client = HttpClient.newHttpClient();
    }

    @AfterAll
    static void stop() {
        if (proxy != null) {
            proxy.stop();
        }
        if (kafka != null) {
            kafka.stop();
        }
    }

    @Test
    void anAsyncWriteIsAckedByTheBrokerAndTheEnvelopeLands() throws Exception {
        var put = HttpRequest.newBuilder(URI.create(base + "/orders/_doc/7"))
                .PUT(HttpRequest.BodyPublishers.ofString("{\"msg\":\"queued\"}"))
                .header("x-tenant", "acme")
                .header("x-osproxy-write-mode", "async")
                .build();
        HttpResponse<String> resp = client.send(put, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(202);
        assertThat(resp.body()).contains("\"status\":\"accepted\"").contains("op_id");

        // The envelope is on the topic, keyed by the physical id and fully
        // transformed (prefixed id, injected marker, routing, epoch).
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "e2e");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (var consumer = new KafkaConsumer<>(
                props, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of("osproxy-writes"));
            ConsumerRecords<String, String> records = ConsumerRecords.empty();
            long deadline = System.currentTimeMillis() + 30_000;
            while (records.isEmpty() && System.currentTimeMillis() < deadline) {
                records = consumer.poll(Duration.ofSeconds(1));
            }
            assertThat(records.count()).isEqualTo(1);
            var record = records.iterator().next();
            assertThat(record.key()).isEqualTo("acme:7");
            assertThat(record.value())
                    .contains("\"op\":\"index\"")
                    .contains("\"physical_id\":\"acme:7\"")
                    .contains("\"routing\":\"acme\"")
                    .contains("\"_tenant\":\"acme\"")
                    .contains("\"msg\":\"queued\"");
        }
    }

    @Test
    void anAsyncBulkEnqueuesEachItemAndTheyAllLandOnTheTopic() throws Exception {
        String ndjson = """
                {"index":{"_index":"orders","_id":"20"}}
                {"msg":"a"}
                {"index":{"_index":"orders","_id":"21"}}
                {"msg":"b"}
                """;
        var post = HttpRequest.newBuilder(URI.create(base + "/_bulk"))
                .POST(HttpRequest.BodyPublishers.ofString(ndjson))
                .header("x-tenant", "acme")
                .header("x-osproxy-write-mode", "async")
                .build();
        HttpResponse<String> resp = client.send(post, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body())
                .contains("\"errors\":false")
                .contains("\"status\":202")
                .contains("\"result\":\"accepted\"");

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "e2e-bulk");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (var consumer = new KafkaConsumer<>(
                props, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of("osproxy-writes"));
            List<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>> seen =
                    new java.util.ArrayList<>();
            long deadline = System.currentTimeMillis() + 30_000;
            while (seen.size() < 2 && System.currentTimeMillis() < deadline) {
                consumer.poll(Duration.ofSeconds(1)).forEach(seen::add);
            }
            assertThat(seen)
                    .extracting(org.apache.kafka.clients.consumer.ConsumerRecord::key)
                    .contains("acme:20", "acme:21");
        }
    }

    @Test
    void aSyncWriteStillRefusesNothing() throws Exception {
        // Without the header the write goes the sync path — and fails against
        // the dead upstream with an upstream error, never a fake success.
        var put = HttpRequest.newBuilder(URI.create(base + "/orders/_doc/8"))
                .PUT(HttpRequest.BodyPublishers.ofString("{}"))
                .header("x-tenant", "acme")
                .build();
        assertThat(client.send(put, HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(502);
    }
}
