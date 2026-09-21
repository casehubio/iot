package io.casehub.iot.webapp.push;

import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class IoTConnectionRegistry {

    private final Map<String, WebSocketConnection> connections = new ConcurrentHashMap<>();

    public void register(String connectionId, WebSocketConnection connection) {
        connections.put(connectionId, connection);
    }

    public void unregister(String connectionId) {
        connections.remove(connectionId);
    }

    public WebSocketConnection get(String connectionId) {
        return connections.get(connectionId);
    }
}
