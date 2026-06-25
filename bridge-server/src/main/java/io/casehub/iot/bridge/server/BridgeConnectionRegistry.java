package io.casehub.iot.bridge.server;

import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class BridgeConnectionRegistry {

    private final ConcurrentHashMap<String, WebSocketConnection> sessions = new ConcurrentHashMap<>();
    private final Set<String> knownTenancies = ConcurrentHashMap.newKeySet();

    public void register(String tenancyId, WebSocketConnection session) {
        knownTenancies.add(tenancyId);
        sessions.put(tenancyId, session);
    }

    public void unregister(String tenancyId) {
        sessions.remove(tenancyId);
    }

    public Optional<WebSocketConnection> getSession(String tenancyId) {
        return Optional.ofNullable(sessions.get(tenancyId));
    }

    /**
     * Return the set of tenancy IDs with active sessions.
     */
    public Set<String> connectedTenancies() {
        return Set.copyOf(sessions.keySet());
    }

    /**
     * True if at least one tenancy has an active session.
     */
    public boolean hasAnyConnection() {
        return !sessions.isEmpty();
    }

    /**
     * True if every known tenancy has an active session.
     * Vacuously true when no tenancies are known.
     */
    public boolean isFullyConnected() {
        return sessions.keySet().containsAll(knownTenancies);
    }

    public Set<String> knownTenancies() {
        return Set.copyOf(knownTenancies);
    }
}
