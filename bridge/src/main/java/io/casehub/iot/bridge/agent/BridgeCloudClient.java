package io.casehub.iot.bridge.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.iot.api.CommandResult;
import io.casehub.iot.api.bridge.BridgeMessage;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.WebSocketClient;
import io.quarkus.websockets.next.WebSocketClientConnection;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;

/**
 * WebSocket client endpoint for the bridge agent. Connects to the cloud
 * bridge server and handles incoming commands and heartbeats.
 *
 * <p>Uses {@code @WebSocketClient} (Quarkus WebSocket Next client API),
 * not {@code @WebSocket} which is the server-side annotation.
 */
@WebSocketClient(path = "/iot/bridge")
public class BridgeCloudClient {

    private static final Logger LOG = Logger.getLogger(BridgeCloudClient.class);

    @Inject
    ObjectMapper mapper;

    @Inject
    BridgeCommandDispatcher commandDispatcher;

    @Inject
    BridgeAgentConfig config;

    @Inject
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "casehub.iot.tenancy-id")
    String tenancyId;

    BridgeCloudClient() {}

    // visible for testing
    BridgeCloudClient(ObjectMapper mapper, BridgeCommandDispatcher commandDispatcher) {
        this.mapper = mapper;
        this.commandDispatcher = commandDispatcher;
    }

    @OnOpen
    void onOpen(WebSocketClientConnection connection) {
        LOG.info("Connected to cloud bridge endpoint");
    }

    @OnClose
    void onClose(WebSocketClientConnection connection) {
        LOG.info("Disconnected from cloud bridge endpoint");
    }

    @OnTextMessage
    void onMessage(String text, WebSocketClientConnection connection) {
        BridgeMessage message;
        try {
            message = mapper.readValue(text, BridgeMessage.class);
        } catch (JsonProcessingException e) {
            LOG.warnf("Failed to deserialize bridge message: %s", e.getMessage());
            return;
        }

        switch (message) {
            case BridgeMessage.Command cmd -> handleCommand(cmd, connection);
            case BridgeMessage.Heartbeat hb -> handleHeartbeat(hb, connection);
            case BridgeMessage.StateChange sc ->
                    LOG.warnf("Received StateChange from cloud — events flow agent-to-server");
            case BridgeMessage.StateSnapshot ss ->
                    LOG.warnf("Received StateSnapshot from cloud — snapshots flow agent-to-server");
            case BridgeMessage.ProviderStatusChange ps ->
                    LOG.warnf("Received ProviderStatusChange from cloud — status flows agent-to-server");
            case BridgeMessage.CommandResponse cr ->
                    LOG.warnf("Received CommandResponse from cloud — responses flow agent-to-server");
            case BridgeMessage.ReplayedStateChange rsc ->
                    LOG.warnf("Received ReplayedStateChange from cloud — replays flow agent-to-server");
        }
    }

    // visible for testing
    void handleCommand(BridgeMessage.Command cmd, WebSocketClientConnection connection) {
        Uni<CommandResult> result;
        try {
            result = commandDispatcher.dispatch(cmd.command());
        } catch (Exception e) {
            LOG.errorf(e, "Command dispatch threw synchronously for correlation %s",
                    cmd.correlationId());
            sendResponse(cmd, CommandResult.FAILED, connection);
            return;
        }
        result.subscribe().with(
                r -> sendResponse(cmd, r, connection),
                failure -> {
                    LOG.errorf(failure, "Command dispatch failed for correlation %s",
                            cmd.correlationId());
                    sendResponse(cmd, CommandResult.FAILED, connection);
                });
    }

    private void sendResponse(BridgeMessage.Command cmd, CommandResult result,
                               WebSocketClientConnection connection) {
        var response = new BridgeMessage.CommandResponse(
                cmd.tenancyId(), Instant.now(), cmd.correlationId(), result);
        try {
            connection.sendTextAndAwait(mapper.writeValueAsString(response));
        } catch (JsonProcessingException e) {
            LOG.errorf("Failed to serialize command response: %s", e.getMessage());
        }
    }

    private void handleHeartbeat(BridgeMessage.Heartbeat hb, WebSocketClientConnection connection) {
        var reply = new BridgeMessage.Heartbeat(tenancyId, Instant.now());
        try {
            connection.sendTextAndAwait(mapper.writeValueAsString(reply));
        } catch (JsonProcessingException e) {
            LOG.errorf("Failed to serialize heartbeat response: %s", e.getMessage());
        }
    }
}
