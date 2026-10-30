package io.casehub.iot.homeassistant;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Map;

/**
 * Picks a free port for the Quarkus test HTTP server and wires the
 * Home Assistant URL config to match, so the embedded {@code FakeHaRestApi}
 * is reachable via the HA REST client without hardcoding port 8081.
 */
public class DynamicTestPortResource implements QuarkusTestResourceLifecycleManager {

    @Override
    public Map<String, String> start() {
        int port = findFreePort();
        return Map.of(
                "quarkus.http.test-port", String.valueOf(port),
                "casehub.iot.homeassistant.url", "http://localhost:" + port
        );
    }

    @Override
    public void stop() {
        // nothing to clean up — Quarkus owns the port lifecycle
    }

    private static int findFreePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException("Could not find a free port", e);
        }
    }
}
