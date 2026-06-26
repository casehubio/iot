package io.casehub.iot.homeassistant;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * JDK-based HTTP server for testing the HA REST client.
 * Replaces OkHttp MockWebServer which has compatibility issues with Vert.x.
 */
public class TestHttpServerResource implements QuarkusTestResourceLifecycleManager {

    static volatile TestHttpServer INSTANCE;

    @Override
    public Map<String, String> start() {
        var server = new TestHttpServer();
        server.start();
        INSTANCE = server;
        String url = "http://localhost:" + server.port();
        return Map.of("casehub.iot.homeassistant.url", url);
    }

    @Override
    public void stop() {
        if (INSTANCE != null) {
            INSTANCE.shutdown();
            INSTANCE = null;
        }
    }

    public static class TestHttpServer {

        private HttpServer server;
        private final ConcurrentLinkedQueue<StubbedResponse> responseQueue = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<RecordedRequest> requestQueue = new ConcurrentLinkedQueue<>();

        void start() {
            try {
                server = HttpServer.create(new InetSocketAddress(0), 0);
            } catch (IOException e) {
                throw new RuntimeException("Failed to create test HTTP server", e);
            }
            server.createContext("/", this::handleRequest);
            server.start();
        }

        public int port() {
            return server.getAddress().getPort();
        }

        public void enqueue(StubbedResponse response) {
            responseQueue.add(response);
        }

        public RecordedRequest takeRequest() {
            return requestQueue.poll();
        }

        void shutdown() {
            if (server != null) {
                server.stop(0);
            }
        }

        public void drainRequests() {
            requestQueue.clear();
        }

        private void handleRequest(HttpExchange exchange) throws IOException {
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            requestQueue.add(new RecordedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    new String(requestBody, StandardCharsets.UTF_8),
                    exchange.getRequestHeaders()));

            StubbedResponse response = responseQueue.poll();
            if (response == null) {
                byte[] msg = "No response enqueued".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, msg.length);
                exchange.getResponseBody().write(msg);
            } else {
                exchange.getResponseHeaders().set("Content-Type", response.contentType());
                byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(response.statusCode(), body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        }
    }

    public record StubbedResponse(int statusCode, String contentType, String body) {
        public static StubbedResponse json(int statusCode, String body) {
            return new StubbedResponse(statusCode, "application/json", body);
        }
    }

    public record RecordedRequest(
            String method,
            String path,
            String body,
            com.sun.net.httpserver.Headers headers) {}
}
