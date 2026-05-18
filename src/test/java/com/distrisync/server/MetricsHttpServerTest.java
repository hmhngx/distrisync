package com.distrisync.server;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsHttpServerTest {

    private HttpServer server;

    @BeforeEach
    void resetCounters() {
        ServerMetrics.FRAMES_DROPPED_TOTAL.set(0);
        ServerMetrics.FRAMES_SENT_TOTAL.set(0);
        ServerMetrics.REDIS_MESSAGES_PUBLISHED.set(0);
        ServerMetrics.REDIS_MESSAGES_RECEIVED.set(0);
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Test
    void formatPrometheusText_exposesCounterValues() {
        ServerMetrics.FRAMES_DROPPED_TOTAL.set(42);
        ServerMetrics.FRAMES_SENT_TOTAL.set(7);
        ServerMetrics.REDIS_MESSAGES_PUBLISHED.set(3);
        ServerMetrics.REDIS_MESSAGES_RECEIVED.set(2);

        String body = ServerMetrics.formatPrometheusText();

        assertThat(body).contains("distrisync_frames_dropped_total 42");
        assertThat(body).contains("distrisync_frames_sent_total 7");
        assertThat(body).contains("distrisync_redis_messages_published 3");
        assertThat(body).contains("distrisync_redis_messages_received 2");
    }

    @Test
    void metricsEndpointReturnsPrometheusPlainText() throws Exception {
        ServerMetrics.FRAMES_DROPPED_TOTAL.set(42);

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/metrics", exchange -> {
            byte[] body = ServerMetrics.formatPrometheusText().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", ServerMetrics.PROMETHEUS_CONTENT_TYPE);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();

        int port = server.getAddress().getPort();
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/metrics")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type"))
                .contains(ServerMetrics.PROMETHEUS_CONTENT_TYPE);
        assertThat(response.body()).contains("distrisync_frames_dropped_total 42");
    }
}
