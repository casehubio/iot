package io.casehub.iot.homeassistant;

import io.casehub.iot.api.ProviderStatus;
import io.casehub.iot.api.ProviderStatusEvent;
import io.casehub.iot.api.StateChangeEvent;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class HomeAssistantWebSocketClientTest {

    private static final Logger LOG = Logger.getLogger(HomeAssistantWebSocketClientTest.class);

    @Inject HomeAssistantWebSocketClient wsClient;
    @Inject FakeHaServer fakeServer;
    @Inject TestEventCollector collector;

    @Test
    void authHandshakeCompletes() throws Exception {
        // The fake server sends auth_required on open, then auth_ok on auth message
        fakeServer.setMode(FakeHaServer.Mode.NORMAL);
        collector.reset();

        wsClient.connect().await().atMost(Duration.ofSeconds(5));

        // Wait for CONNECTED status event
        assertThat(collector.awaitStatus(ProviderStatus.CONNECTED, 5, TimeUnit.SECONDS))
            .as("Should reach CONNECTED after auth handshake")
            .isTrue();
        assertThat(wsClient.currentStatus()).isEqualTo(ProviderStatus.CONNECTED);
    }

    @Test
    void stateChangedFiresCdiEvent() throws Exception {
        fakeServer.setMode(FakeHaServer.Mode.NORMAL);
        collector.reset();

        wsClient.connect().await().atMost(Duration.ofSeconds(5));
        collector.awaitStatus(ProviderStatus.CONNECTED, 5, TimeUnit.SECONDS);

        // Server sends a state_changed event
        String stateChanged = """
            {"id":1,"type":"event","event":{"event_type":"state_changed","data":{
              "new_state":{"entity_id":"switch.hallway","state":"on","last_updated":"2026-06-09T10:00:00Z","last_changed":"2026-06-09T09:55:00Z","attributes":{"friendly_name":"Hallway Switch"}},
              "old_state":{"entity_id":"switch.hallway","state":"off","last_updated":"2026-06-09T09:50:00Z","last_changed":"2026-06-09T09:50:00Z","attributes":{"friendly_name":"Hallway Switch"}}
            }}}""";
        fakeServer.sendToAll(stateChanged);

        assertThat(collector.awaitStateChange(5, TimeUnit.SECONDS))
            .as("Should receive state change event")
            .isTrue();

        StateChangeEvent event = collector.lastStateChange();
        assertThat(event).isNotNull();
        assertThat(event.after().deviceId()).isEqualTo("switch.hallway");
        assertThat(event.before()).isNotNull();
        assertThat(event.before().deviceId()).isEqualTo("switch.hallway");
        assertThat(event.providerId()).isEqualTo("homeassistant");
    }

    @Test
    void nullOldStateUsesAllKeys() throws Exception {
        fakeServer.setMode(FakeHaServer.Mode.NORMAL);
        collector.reset();

        wsClient.connect().await().atMost(Duration.ofSeconds(5));
        collector.awaitStatus(ProviderStatus.CONNECTED, 5, TimeUnit.SECONDS);

        // state_changed with null old_state (new entity)
        String stateChanged = """
            {"id":1,"type":"event","event":{"event_type":"state_changed","data":{
              "new_state":{"entity_id":"switch.new_device","state":"on","last_updated":"2026-06-09T10:00:00Z","last_changed":"2026-06-09T10:00:00Z","attributes":{"friendly_name":"New Device"}},
              "old_state":null
            }}}""";
        fakeServer.sendToAll(stateChanged);

        assertThat(collector.awaitStateChange(5, TimeUnit.SECONDS))
            .as("Should receive state change event for new entity")
            .isTrue();

        StateChangeEvent event = collector.lastStateChange();
        assertThat(event).isNotNull();
        assertThat(event.before()).isNull();
        assertThat(event.changedCapabilities()).isNotEmpty();
        // All capability keys should be in changedCapabilities since there is no previous state
        assertThat(event.changedCapabilities()).containsAll(event.after().capabilities().keySet());
    }

    @Test
    void unparsableMessageIgnored() throws Exception {
        fakeServer.setMode(FakeHaServer.Mode.NORMAL);
        collector.reset();

        wsClient.connect().await().atMost(Duration.ofSeconds(5));
        collector.awaitStatus(ProviderStatus.CONNECTED, 5, TimeUnit.SECONDS);

        // Send garbage — should not crash
        fakeServer.sendToAll("this is not json {{{");

        // Send a valid state_changed after the garbage to prove the connection is still alive
        String stateChanged = """
            {"id":1,"type":"event","event":{"event_type":"state_changed","data":{
              "new_state":{"entity_id":"switch.still_alive","state":"on","last_updated":"2026-06-09T10:00:00Z","last_changed":"2026-06-09T10:00:00Z","attributes":{}},
              "old_state":null
            }}}""";
        fakeServer.sendToAll(stateChanged);

        assertThat(collector.awaitStateChange(5, TimeUnit.SECONDS))
            .as("Connection should survive unparseable message")
            .isTrue();
        assertThat(collector.lastStateChange().after().deviceId()).isEqualTo("switch.still_alive");
    }

    @Test
    void authInvalidTriggersDisconnected() throws Exception {
        fakeServer.setMode(FakeHaServer.Mode.AUTH_INVALID);
        collector.reset();

        wsClient.connect().await().atMost(Duration.ofSeconds(5));

        // Should go CONNECTING then get auth_invalid
        // The client fires CONNECTING on open, then schedules reconnect on auth_invalid
        // The @OnClose handler fires DISCONNECTED when the connection drops
        assertThat(collector.awaitStatus(ProviderStatus.CONNECTING, 5, TimeUnit.SECONDS))
            .as("Should reach CONNECTING on open")
            .isTrue();
    }

    // ---- Embedded server and event collector beans ----

    /**
     * Fake Home Assistant WebSocket server running inside the same Quarkus test instance.
     * Simulates the HA auth handshake and can send arbitrary messages to connected clients.
     */
    @WebSocket(path = "/api/websocket")
    @ApplicationScoped
    public static class FakeHaServer {

        private static final Logger LOG = Logger.getLogger(FakeHaServer.class);

        enum Mode { NORMAL, AUTH_INVALID }

        private volatile Mode mode = Mode.NORMAL;
        private final List<WebSocketConnection> connections = new CopyOnWriteArrayList<>();

        public void setMode(Mode mode) {
            this.mode = mode;
        }

        @OnOpen
        public String onOpen(WebSocketConnection conn) {
            connections.add(conn);
            LOG.infof("FakeHaServer: client connected, sending auth_required");
            return "{\"type\":\"auth_required\",\"ha_version\":\"2024.6.0\"}";
        }

        @OnTextMessage
        public String onMessage(String text, WebSocketConnection conn) {
            LOG.infof("FakeHaServer received: %s", text);
            if (text.contains("\"type\":\"auth\"")) {
                if (mode == Mode.AUTH_INVALID) {
                    return "{\"type\":\"auth_invalid\",\"message\":\"Invalid access token\"}";
                }
                return "{\"type\":\"auth_ok\",\"ha_version\":\"2024.6.0\"}";
            }
            if (text.contains("\"type\":\"subscribe_events\"")) {
                // Extract id from the message for the result response
                int idStart = text.indexOf("\"id\":") + 5;
                int idEnd = text.indexOf(",", idStart);
                if (idEnd < 0) idEnd = text.indexOf("}", idStart);
                String id = text.substring(idStart, idEnd).trim();
                return "{\"id\":" + id + ",\"type\":\"result\",\"success\":true}";
            }
            if (text.contains("\"type\":\"ping\"")) {
                int idStart = text.indexOf("\"id\":") + 5;
                int idEnd = text.indexOf(",", idStart);
                if (idEnd < 0) idEnd = text.indexOf("}", idStart);
                String id = text.substring(idStart, idEnd).trim();
                return "{\"id\":" + id + ",\"type\":\"pong\"}";
            }
            return null;
        }

        public void sendToAll(String message) {
            for (WebSocketConnection conn : connections) {
                if (conn.isOpen()) {
                    conn.sendTextAndAwait(message);
                }
            }
        }
    }

    /**
     * CDI observer that collects async events for test assertions.
     */
    @ApplicationScoped
    public static class TestEventCollector {

        private volatile CountDownLatch statusLatch;
        private volatile ProviderStatus awaitedStatus;
        private volatile CountDownLatch stateChangeLatch;
        private final List<ProviderStatusEvent> statusEvents = new CopyOnWriteArrayList<>();
        private final List<StateChangeEvent> stateChangeEvents = new CopyOnWriteArrayList<>();

        public void reset() {
            statusEvents.clear();
            stateChangeEvents.clear();
            statusLatch = null;
            stateChangeLatch = null;
            awaitedStatus = null;
        }

        public void onStatus(@ObservesAsync ProviderStatusEvent event) {
            LOG.infof("TestEventCollector: status %s -> %s", event.previousStatus(), event.currentStatus());
            statusEvents.add(event);
            if (statusLatch != null && event.currentStatus() == awaitedStatus) {
                statusLatch.countDown();
            }
        }

        public void onStateChange(@ObservesAsync StateChangeEvent event) {
            LOG.infof("TestEventCollector: state change for %s", event.after().deviceId());
            stateChangeEvents.add(event);
            if (stateChangeLatch != null) {
                stateChangeLatch.countDown();
            }
        }

        public boolean awaitStatus(ProviderStatus status, long timeout, TimeUnit unit) throws InterruptedException {
            // Check if already received
            if (statusEvents.stream().anyMatch(e -> e.currentStatus() == status)) {
                return true;
            }
            statusLatch = new CountDownLatch(1);
            awaitedStatus = status;
            // Double-check after setting latch (race window)
            if (statusEvents.stream().anyMatch(e -> e.currentStatus() == status)) {
                return true;
            }
            return statusLatch.await(timeout, unit);
        }

        public boolean awaitStateChange(long timeout, TimeUnit unit) throws InterruptedException {
            if (!stateChangeEvents.isEmpty()) {
                return true;
            }
            stateChangeLatch = new CountDownLatch(1);
            if (!stateChangeEvents.isEmpty()) {
                return true;
            }
            return stateChangeLatch.await(timeout, unit);
        }

        public StateChangeEvent lastStateChange() {
            return stateChangeEvents.isEmpty() ? null : stateChangeEvents.getLast();
        }

        private static final Logger LOG = Logger.getLogger(TestEventCollector.class);
    }
}
