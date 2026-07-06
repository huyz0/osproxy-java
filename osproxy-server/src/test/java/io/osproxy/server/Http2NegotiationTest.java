package io.osproxy.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.helidon.webclient.api.HttpClientResponse;
import io.helidon.webclient.api.WebClient;
import io.helidon.webclient.http2.Http2Client;
import io.helidon.webclient.http2.Http2ClientProtocolConfig;
import io.osproxy.core.ClusterId;
import io.osproxy.core.IndexName;
import io.osproxy.engine.Pipeline;
import io.osproxy.sink.MemorySink;
import io.osproxy.tenancy.TenancyRouter;
import io.helidon.webserver.WebServer;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Proves HTTP/2 actually negotiates and drives the ordinary REST routing,
 * not just that {@code helidon-webserver-http2} is on the classpath: a
 * client speaking h2c prior knowledge (no HTTP/1.1 upgrade dance) gets the
 * same responses {@link ServerLoopbackTest} gets over HTTP/1.1.
 */
class Http2NegotiationTest {

    private static WebServer server;
    private static WebClient client;

    @BeforeAll
    static void start() {
        MemorySink sink = new MemorySink();
        Pipeline pipeline = new Pipeline(
                new TenancyRouter(new ReferenceTenancy(
                        new ClusterId("primary"), new IndexName("shared"))),
                sink, sink);
        AppHandler handler = new AppHandler(pipeline, new BearerAuth(Map.of("secret-acme", "acme")));
        server = WebServer.builder().port(0).routing(handler::route).build().start();
        client = WebClient.builder()
                .baseUri("http://localhost:" + server.port())
                .addProtocolConfig(Http2ClientProtocolConfig.builder().priorKnowledge(true).build())
                .build();
    }

    @AfterAll
    static void stop() {
        server.stop();
    }

    @Test
    void ingestAndReadRoundTripOverHttp2PriorKnowledge() {
        Http2Client http2 = client.client(Http2Client.PROTOCOL);
        try (HttpClientResponse put = http2.put("/orders/_doc/7")
                .header(io.helidon.http.HeaderNames.AUTHORIZATION, "Bearer secret-acme")
                .header(io.helidon.http.HeaderNames.CONTENT_TYPE, "application/json")
                .submit("{\"msg\":\"hi\"}")) {
            assertThat(put.status().code()).isEqualTo(201);
        }
        try (HttpClientResponse got = http2.get("/orders/_doc/7")
                .header(io.helidon.http.HeaderNames.AUTHORIZATION, "Bearer secret-acme")
                .request()) {
            assertThat(got.status().code()).isEqualTo(200);
            assertThat(got.as(String.class)).contains("\"msg\":\"hi\"");
        }
    }
}
