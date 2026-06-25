package io.casehub.iot.bridge.server;

import io.quarkus.websockets.next.WebSocketConnection;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.assertj.core.api.Assertions.assertThat;

class BridgeReadinessCheckTest {

    private BridgeConnectionRegistry registry;
    private BridgeReadinessCheck check;

    @BeforeEach
    void setUp() {
        registry = new BridgeConnectionRegistry();
        check = new BridgeReadinessCheck(registry);
    }

    @Test
    void downWhenNoAgentsConnected() {
        HealthCheckResponse response = check.call();

        assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.Status.DOWN);
        assertThat(response.getData()).isPresent();
        assertThat(response.getData().get()).containsEntry("connectedAgents", 0L);
        assertThat(response.getData().get()).containsEntry("knownAgents", 0L);
    }

    @Test
    void upWhenAgentConnected() {
        registry.register("tenant-1", mockConnection());

        HealthCheckResponse response = check.call();

        assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.Status.UP);
        assertThat(response.getData()).isPresent();
        assertThat(response.getData().get()).containsEntry("connectedAgents", 1L);
        assertThat(response.getData().get()).containsEntry("knownAgents", 1L);
    }

    @Test
    void downAfterAgentDisconnects() {
        registry.register("tenant-1", mockConnection());
        registry.unregister("tenant-1");

        HealthCheckResponse response = check.call();

        assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.Status.DOWN);
        assertThat(response.getData().get()).containsEntry("connectedAgents", 0L);
        assertThat(response.getData().get()).containsEntry("knownAgents", 1L);
    }

    private static WebSocketConnection mockConnection() {
        return (WebSocketConnection) Proxy.newProxyInstance(
                WebSocketConnection.class.getClassLoader(),
                new Class[]{WebSocketConnection.class},
                (proxy, method, args) -> null);
    }
}
