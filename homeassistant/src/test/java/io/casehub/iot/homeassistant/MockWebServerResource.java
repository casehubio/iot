package io.casehub.iot.homeassistant;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import okhttp3.mockwebserver.MockWebServer;

import java.io.IOException;
import java.util.Map;

/**
 * Starts an OkHttp {@link MockWebServer} before the Quarkus test container boots
 * and feeds its URL into {@code quarkus.rest-client."homeassistant".url}.
 *
 * <p>The server instance is shared via {@link #INSTANCE} so tests can enqueue
 * responses and inspect recorded requests.
 */
public class MockWebServerResource implements QuarkusTestResourceLifecycleManager {

    static volatile MockWebServer INSTANCE;

    @Override
    public Map<String, String> start() {
        var server = new MockWebServer();
        try {
            server.start();
        } catch (IOException e) {
            throw new RuntimeException("Failed to start MockWebServer", e);
        }
        INSTANCE = server;
        String url = "http://localhost:" + server.getPort();
        return Map.of("quarkus.rest-client.\"homeassistant\".url", url);
    }

    @Override
    public void stop() {
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
