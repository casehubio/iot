package io.casehub.iot.webapp.push;

import io.casehub.pages.push.SessionSender;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class IoTSessionSender implements SessionSender {

    @Inject IoTConnectionRegistry registry;

    @Override
    public void send(String connectionId, String message) {
        WebSocketConnection conn = registry.get(connectionId);
        if (conn != null && !conn.isClosed()) {
            conn.sendTextAndAwait(message);
        }
    }
}
