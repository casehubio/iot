package io.casehub.iot.webapp.push;

import io.casehub.pages.push.EventStore;
import io.casehub.pages.push.PushMessage;
import io.casehub.pages.push.PushRequest;
import io.casehub.pages.push.StoredEvent;
import io.casehub.pages.push.TopicRegistry;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.UserData;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@WebSocket(path = "/push")
public class IoTPushEndpoint {

    static final UserData.TypedKey<String> CONN_ID_KEY = new UserData.TypedKey<>("connId");

    @Inject IoTConnectionRegistry connectionRegistry;
    @Inject TopicRegistry topicRegistry;
    @Inject EventStore eventStore;

    @OnOpen
    void onOpen(WebSocketConnection connection) {
        String connId = UUID.randomUUID().toString();
        connection.userData().put(CONN_ID_KEY, connId);
        connectionRegistry.register(connId, connection);
    }

    @OnTextMessage
    void onMessage(WebSocketConnection connection, String message) {
        String connId = connection.userData().get(CONN_ID_KEY);
        PushRequest request = PushRequest.parse(message);

        switch (request) {
            case PushRequest.Listen listen -> {
                topicRegistry.listen(connId, listen.topics());

                List<String> gaps = new ArrayList<>();
                for (var entry : listen.since().entrySet()) {
                    List<StoredEvent> events = eventStore.replay(entry.getKey(), entry.getValue(), 1000);
                    if (events.isEmpty() && entry.getValue() > 0) {
                        gaps.add(entry.getKey());
                    }
                    for (var stored : events) {
                        connection.sendTextAndAwait(
                                PushMessage.event(stored.topic(), stored.payloadJson(), stored.seq()));
                    }
                }
                connection.sendTextAndAwait(
                        PushMessage.ack(listen.id(), listen.topics(), gaps));
            }
            case PushRequest.Unlisten unlisten -> {
                topicRegistry.unlisten(connId, unlisten.topics());
                connection.sendTextAndAwait(
                        PushMessage.ack(unlisten.id(), unlisten.topics(), List.of()));
            }
            default -> connection.sendTextAndAwait(
                    PushMessage.error(request.id(), "Unsupported op: " + request.op()));
        }
    }

    @OnClose
    void onClose(WebSocketConnection connection) {
        String connId = connection.userData().get(CONN_ID_KEY);
        if (connId != null) {
            topicRegistry.removeConnection(connId);
            connectionRegistry.unregister(connId);
        }
    }
}
