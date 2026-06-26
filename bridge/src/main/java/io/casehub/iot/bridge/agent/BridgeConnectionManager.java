package io.casehub.iot.bridge.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.iot.api.DeviceEntity;
import io.casehub.iot.api.bridge.BridgeMessage;
import io.casehub.iot.api.spi.DeviceProvider;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.websockets.next.WebSocketClientConnection;
import io.quarkus.websockets.next.WebSocketConnector;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages the WebSocket connection lifecycle between the bridge agent
 * and the cloud endpoint. Handles initial connection, snapshot sending,
 * and reconnection with exponential backoff.
 *
 * <p>Connection lifecycle:
 * <ol>
 *   <li>Startup: connect with auth headers</li>
 *   <li>On connect: discover all local devices, send {@link BridgeMessage.StateSnapshot}</li>
 *   <li>On disconnect: schedule reconnect with exponential backoff</li>
 *   <li>On reconnect: send fresh snapshot</li>
 * </ol>
 */
@ApplicationScoped
public class BridgeConnectionManager {

    private static final Logger LOG = Logger.getLogger(BridgeConnectionManager.class);

    @Inject
    BridgeAgentConfig config;

    @Inject
    ObjectMapper mapper;

    @Inject
    @Any
    Instance<DeviceProvider> providers;

    @Inject
    WebSocketConnector<BridgeCloudClient> connector;

    @Inject
    BridgeEventStore eventStore;

    @Inject
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "casehub.iot.tenancy-id")
    String tenancyId;

    private final AtomicReference<WebSocketClientConnection> connection = new AtomicReference<>();

    void onStartup(@Observes StartupEvent event) {
        connect();
    }

    /**
     * Returns {@code true} if the WebSocket connection is currently open.
     */
    public boolean isConnected() {
        var conn = connection.get();
        return conn != null && conn.isOpen();
    }

    /**
     * Serializes and sends a {@link BridgeMessage} over the WebSocket connection.
     * Silently drops the message if the connection is not open.
     */
    public void send(BridgeMessage message) {
        var conn = connection.get();
        if (conn == null || !conn.isOpen()) {
            LOG.debugf("Cannot send message — not connected: %s",
                    message.getClass().getSimpleName());
            return;
        }
        try {
            conn.sendTextAndAwait(mapper.writeValueAsString(message));
        } catch (JsonProcessingException e) {
            LOG.errorf("Failed to serialize bridge message: %s", e.getMessage());
        }
    }

    private void connect() {
        URI uri = URI.create(config.cloudEndpoint());
        try {
            WebSocketClientConnection conn = connector
                    .baseUri(uri)
                    .addHeader("Authorization", "Bearer " + config.token())
                    .addHeader("X-Tenancy-ID", tenancyId)
                    .connectAndAwait();
            connection.set(conn);
            LOG.infof("Bridge agent connected to %s", uri);
            replayBufferedEvents();
            sendSnapshot();
        } catch (Exception e) {
            LOG.warnf("Failed to connect to cloud endpoint %s: %s",
                    uri, e.getMessage());
            scheduleReconnect(config.reconnectBaseSeconds());
        }
    }

    private void replayBufferedEvents() {
        if (eventStore.isEmpty()) return;
        List<BridgeMessage> buffered = eventStore.drain();
        LOG.infof("Replaying %d buffered events", buffered.size());
        for (BridgeMessage msg : buffered) {
            send(markReplayed(msg));
        }
    }

    private BridgeMessage markReplayed(BridgeMessage msg) {
        if (msg instanceof BridgeMessage.StateChange sc) {
            return new BridgeMessage.ReplayedStateChange(sc.tenancyId(), sc.timestamp(), sc.event());
        }
        return msg;
    }

    private void sendSnapshot() {
        List<DeviceEntity> allDevices = new ArrayList<>();
        for (DeviceProvider provider : providers) {
            try {
                List<DeviceEntity> devices = provider.discover()
                        .await().indefinitely();
                allDevices.addAll(devices);
            } catch (Exception e) {
                LOG.warnf("Failed to discover devices from provider %s: %s",
                        provider.providerId(), e.getMessage());
            }
        }
        var snapshot = new BridgeMessage.StateSnapshot(
                tenancyId, Instant.now(), allDevices);
        send(snapshot);
        LOG.infof("Sent state snapshot with %d devices", allDevices.size());
    }

    private void scheduleReconnect(int initialDelaySeconds) {
        Thread.ofVirtual().name("bridge-reconnect").start(() -> {
            int delay = Math.min(initialDelaySeconds, config.reconnectMaxSeconds());
            while (!Thread.currentThread().isInterrupted()) {
                LOG.infof("Reconnecting in %d seconds", delay);
                try {
                    Thread.sleep(delay * 1000L);
                    connect();
                    return;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    LOG.debug("Reconnect thread interrupted");
                    return;
                } catch (Exception e) {
                    LOG.warnf("Reconnect attempt failed: %s", e.getMessage());
                    delay = Math.min(delay * 2, config.reconnectMaxSeconds());
                }
            }
        });
    }
}
