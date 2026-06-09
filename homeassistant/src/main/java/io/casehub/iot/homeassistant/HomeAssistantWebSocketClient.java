package io.casehub.iot.homeassistant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.iot.api.DeviceEntity;
import io.casehub.iot.api.ProviderStatus;
import io.casehub.iot.api.ProviderStatusEvent;
import io.casehub.iot.api.StateChangeEvent;
import io.casehub.iot.homeassistant.internal.HaStateDto;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnError;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.WebSocketClient;
import io.quarkus.websockets.next.WebSocketClientConnection;
import io.quarkus.websockets.next.WebSocketConnector;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@WebSocketClient(clientId = "homeassistant", path = "/api/websocket")
@ApplicationScoped
public class HomeAssistantWebSocketClient {

    private static final Logger LOG = Logger.getLogger(HomeAssistantWebSocketClient.class);

    @Inject HomeAssistantConfig config;
    @Inject HomeAssistantEntityMapper mapper;
    @Inject Event<StateChangeEvent> stateEvents;
    @Inject Event<ProviderStatusEvent> statusEvents;
    @Inject ObjectMapper objectMapper;
    @Inject Instance<WebSocketConnector<HomeAssistantWebSocketClient>> connectorProvider;

    private volatile WebSocketClientConnection connection;
    private volatile ProviderStatus currentStatus = ProviderStatus.DISCONNECTED;
    private volatile boolean shuttingDown = false;
    private volatile ScheduledFuture<?> heartbeatFuture;
    private volatile ScheduledFuture<?> pongTimeoutFuture;
    private final AtomicInteger messageId = new AtomicInteger(0);
    private final AtomicInteger reconnectAttempt = new AtomicInteger(0);
    private final ScheduledExecutorService executor =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ha-reconnect");
            t.setDaemon(true);
            return t;
        });

    private int nextId() {
        return messageId.getAndUpdate(n -> n == Integer.MAX_VALUE ? 1 : n + 1);
    }

    public Uni<Void> connect() {
        return connectorProvider.get()
            .baseUri(URI.create(config.url()))
            .connect()
            .invoke(c -> this.connection = c)
            .replaceWithVoid();
    }

    public ProviderStatus currentStatus() {
        return currentStatus;
    }

    // ---- WebSocket lifecycle handlers ----

    @OnOpen
    public void onOpen(WebSocketClientConnection conn) {
        LOG.info("HA WebSocket connection opened — awaiting auth_required");
        fireStatus(ProviderStatus.CONNECTING);
    }

    @OnTextMessage
    public Uni<Void> onMessage(String text, WebSocketClientConnection conn) {
        JsonNode msg;
        try {
            msg = objectMapper.readTree(text);
        } catch (JsonProcessingException e) {
            LOG.warnf(e, "Ignoring unparseable HA message: %s", text);
            return Uni.createFrom().voidItem();
        }
        String type = msg.path("type").asText("");
        return switch (type) {
            case "auth_required" -> sendAuth(conn);
            case "auth_ok"       -> onAuthOk(conn);
            case "auth_invalid"  -> {
                LOG.error("HA auth invalid — check token");
                scheduleReconnect();
                yield Uni.createFrom().voidItem();
            }
            case "event"         -> onStateChanged(msg);
            case "pong"          -> {
                cancelPongTimeout();
                reconnectAttempt.set(0);
                yield Uni.createFrom().voidItem();
            }
            case "result"        -> {
                handleResult(msg);
                yield Uni.createFrom().voidItem();
            }
            default              -> {
                LOG.warnf("Unrecognised HA message type: %s", type);
                yield Uni.createFrom().voidItem();
            }
        };
    }

    @OnClose
    public void onClose(WebSocketClientConnection conn) {
        if (!shuttingDown) {
            LOG.info("HA WebSocket connection closed");
            cancelHeartbeat();
            cancelPongTimeout();
            fireStatus(ProviderStatus.DISCONNECTED);
            scheduleReconnect();
        }
    }

    @OnError
    public void onError(WebSocketClientConnection conn, Throwable error) {
        if (!shuttingDown) {
            LOG.warnf(error, "HA WebSocket error");
            cancelHeartbeat();
            cancelPongTimeout();
            fireStatus(ProviderStatus.DISCONNECTED);
            scheduleReconnect();
        }
    }

    @PreDestroy
    public void stop() {
        shuttingDown = true;
        cancelHeartbeat();
        cancelPongTimeout();
        WebSocketClientConnection conn = connection;
        if (conn != null && !conn.isClosed()) {
            conn.closeAndAwait();
        }
        executor.shutdownNow();
    }

    // ---- Private helpers ----

    private Uni<Void> sendAuth(WebSocketClientConnection conn) {
        String authJson = "{\"type\":\"auth\",\"access_token\":\"" + config.token() + "\"}";
        return conn.sendText(authJson).replaceWithVoid();
    }

    private Uni<Void> onAuthOk(WebSocketClientConnection conn) {
        int id = nextId();
        String subscribeJson = "{\"id\":" + id + ",\"type\":\"subscribe_events\",\"event_type\":\"state_changed\"}";
        fireStatus(ProviderStatus.CONNECTED);
        reconnectAttempt.set(0);
        heartbeatFuture = executor.scheduleAtFixedRate(this::sendHeartbeat,
            config.pingIntervalSeconds(), config.pingIntervalSeconds(), TimeUnit.SECONDS);
        return conn.sendText(subscribeJson).replaceWithVoid();
    }

    private Uni<Void> onStateChanged(JsonNode msg) {
        try {
            JsonNode eventData = msg.path("event").path("data");
            JsonNode newStateNode = eventData.path("new_state");
            JsonNode oldStateNode = eventData.path("old_state");

            if (newStateNode.isMissingNode() || newStateNode.isNull()) {
                LOG.warn("state_changed event with null new_state — ignoring");
                return Uni.createFrom().voidItem();
            }

            HaStateDto newDto = objectMapper.treeToValue(newStateNode, HaStateDto.class);
            DeviceEntity after = mapper.mapOne(newDto);
            if (after == null) {
                return Uni.createFrom().voidItem();
            }

            DeviceEntity before = null;
            Set<String> changedCapabilities;
            if (oldStateNode.isMissingNode() || oldStateNode.isNull()) {
                changedCapabilities = Set.copyOf(after.capabilities().keySet());
            } else {
                HaStateDto oldDto = objectMapper.treeToValue(oldStateNode, HaStateDto.class);
                before = mapper.mapOne(oldDto);
                if (before == null) {
                    changedCapabilities = Set.copyOf(after.capabilities().keySet());
                } else {
                    changedCapabilities = StateChangeEvent.deriveChangedCapabilities(before, after);
                }
            }

            stateEvents.fireAsync(new StateChangeEvent(before, after, changedCapabilities,
                Instant.now(), "homeassistant"));
        } catch (Exception e) {
            LOG.warnf(e, "Failed to process state_changed event — ignoring");
        }
        return Uni.createFrom().voidItem();
    }

    private void handleResult(JsonNode msg) {
        if (!msg.path("success").asBoolean(true)) {
            LOG.warnf("HA result failure: %s", msg.path("error").path("message").asText("unknown"));
        }
    }

    private void fireStatus(ProviderStatus newStatus) {
        ProviderStatus oldStatus = currentStatus;
        currentStatus = newStatus;
        statusEvents.fireAsync(new ProviderStatusEvent("homeassistant", oldStatus, newStatus));
    }

    private void sendHeartbeat() {
        WebSocketClientConnection conn = connection;
        if (conn == null || conn.isClosed()) return;
        try {
            conn.sendTextAndAwait("{\"id\":" + nextId() + ",\"type\":\"ping\"}");
            pongTimeoutFuture = executor.schedule(this::handlePongTimeout,
                config.pongTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.warnf(e, "Failed to send heartbeat ping");
            scheduleReconnect();
        }
    }

    private void handlePongTimeout() {
        LOG.warn("HA WebSocket pong timeout — reconnecting");
        WebSocketClientConnection conn = connection;
        if (conn != null) {
            conn.closeAndAwait();
        }
        scheduleReconnect();
    }

    void scheduleReconnect() {
        if (shuttingDown) return;
        int attempt = reconnectAttempt.getAndIncrement();
        double base = config.reconnectBaseSeconds() * Math.pow(2, attempt);
        double capped = Math.min(base, config.reconnectMaxSeconds());
        double jittered = capped * (0.75 + 0.5 * ThreadLocalRandom.current().nextDouble());
        LOG.infof("Scheduling HA reconnect attempt %d in %.1fs", attempt + 1, jittered);
        executor.schedule(() -> connect().subscribe().with(
            v -> {},
            e -> {
                LOG.warnf(e, "HA reconnect attempt %d failed", attempt + 1);
                scheduleReconnect();
            }
        ), (long) (jittered * 1000), TimeUnit.MILLISECONDS);
    }

    private void cancelHeartbeat() {
        ScheduledFuture<?> f = heartbeatFuture;
        if (f != null) {
            f.cancel(true);
            heartbeatFuture = null;
        }
    }

    private void cancelPongTimeout() {
        ScheduledFuture<?> f = pongTimeoutFuture;
        if (f != null) {
            f.cancel(false);
            pongTimeoutFuture = null;
        }
    }
}
