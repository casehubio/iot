package io.casehub.iot.openhab;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Starts an OkHttp {@link MockWebServer} before the Quarkus test container boots
 * and feeds its URL into the REST client config properties.
 *
 * <p>Uses a custom dispatcher to handle the SSE client's background requests
 * ({@code @PostConstruct} connect, reconnect) without interfering with
 * test-specific assertions.</p>
 *
 * <h3>Dispatcher strategy</h3>
 * <ul>
 *   <li>SSE events ({@code /rest/events}) — always returns empty stream</li>
 *   <li>Equipment discovery ({@code /rest/items?tags=Equipment}) — returns
 *       a response built from {@link #equipmentBody}, configurable per test</li>
 *   <li>Item commands ({@code POST /rest/items/}) — served from {@link #commandResponses} queue</li>
 *   <li>Everything else — 404</li>
 * </ul>
 */
public class OpenHabMockServerResource implements QuarkusTestResourceLifecycleManager {

    static volatile MockWebServer INSTANCE;

    /** JSON body to return for Equipment discovery requests. Defaults to empty list. */
    static final AtomicReference<String> equipmentBody = new AtomicReference<>("[]");

    /** Queue for item command (POST) responses. */
    static final LinkedBlockingDeque<MockResponse> commandResponses = new LinkedBlockingDeque<>();

    /** Last Authorization header seen on a non-SSE request. */
    static final AtomicReference<String> lastAuthHeader = new AtomicReference<>();

    /**
     * Set the JSON body for Equipment discovery responses.
     * Every {@code GET /rest/items?tags=Equipment} request returns this body
     * until it is changed.
     */
    static void setEquipmentBody(String body) {
        equipmentBody.set(body);
    }

    /**
     * Enqueue a response for an item command POST request.
     */
    static void enqueueCommandResponse(MockResponse response) {
        commandResponses.add(response);
    }

    /**
     * Reset all responses to defaults.
     */
    static void reset() {
        equipmentBody.set("[]");
        commandResponses.clear();
        lastAuthHeader.set(null);
    }

    @Override
    public Map<String, String> start() {
        var server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath();
                lastAuthHeader.set(request.getHeader("Authorization"));

                // SSE subscription — return empty stream (closes immediately → triggers reconnect)
                if (path != null && path.contains("/rest/events")) {
                    return new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "text/event-stream")
                        .setBody("");
                }

                // Equipment discovery — serve from the configured body string
                if (path != null && path.contains("/rest/items") && path.contains("tags=Equipment")) {
                    return new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody(equipmentBody.get());
                }

                // Item command POST — serve from queue
                if (path != null && path.contains("/rest/items/") && "POST".equals(request.getMethod())) {
                    MockResponse resp = commandResponses.pollFirst();
                    if (resp != null) {
                        return resp;
                    }
                    return new MockResponse().setResponseCode(200);
                }

                return new MockResponse().setResponseCode(404);
            }
        });

        try {
            server.start();
        } catch (IOException e) {
            throw new RuntimeException("Failed to start MockWebServer", e);
        }
        INSTANCE = server;
        String url = "http://localhost:" + server.getPort();
        return Map.of(
            "quarkus.rest-client.\"openhab\".url", url,
            "quarkus.rest-client.\"openhab-sse\".url", url,
            "casehub.iot.openhab.url", url
        );
    }

    @Override
    public void stop() {
        reset();
        if (INSTANCE != null) {
            try {
                INSTANCE.shutdown();
            } catch (IOException e) {
                // best-effort shutdown
            }
            INSTANCE = null;
        }
    }
}
