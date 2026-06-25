package io.casehub.iot.bridge.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.casehub.iot.api.CommandResult;
import io.casehub.iot.api.DeviceCommand;
import io.casehub.iot.api.bridge.BridgeMessage;
import io.quarkus.websockets.next.WebSocketClientConnection;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BridgeCloudClientTest {

    private ObjectMapper mapper;
    private List<String> sentMessages;
    private WebSocketClientConnection mockConnection;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        sentMessages = new ArrayList<>();
        mockConnection = createMockConnection(sentMessages);
    }

    @Test
    void dispatchFailureSendsFailedResponse() throws Exception {
        var failingDispatcher = new BridgeCommandDispatcher(List.of(), new EmptyRegistry());
        var client = new BridgeCloudClient(mapper, failingDispatcher);

        var cmd = new BridgeMessage.Command(
                "tenant-1", Instant.now(), "corr-1",
                new DeviceCommand("device-1", "turn_on", Map.of(), "test", "corr-1"));

        client.handleCommand(cmd, mockConnection);

        assertThat(sentMessages).hasSize(1);
        BridgeMessage response = mapper.readValue(sentMessages.get(0), BridgeMessage.class);
        assertThat(response).isInstanceOf(BridgeMessage.CommandResponse.class);
        var cr = (BridgeMessage.CommandResponse) response;
        assertThat(cr.result()).isEqualTo(CommandResult.FAILED);
        assertThat(cr.correlationId()).isEqualTo("corr-1");
    }

    @Test
    void dispatchSuccessSendsResult() throws Exception {
        var successDispatcher = new StubDispatcher(CommandResult.SENT);
        var client = new BridgeCloudClient(mapper, successDispatcher);

        var cmd = new BridgeMessage.Command(
                "tenant-1", Instant.now(), "corr-2",
                new DeviceCommand("device-1", "turn_on", Map.of(), "test", "corr-2"));

        client.handleCommand(cmd, mockConnection);

        assertThat(sentMessages).hasSize(1);
        var cr = (BridgeMessage.CommandResponse) mapper.readValue(sentMessages.get(0), BridgeMessage.class);
        assertThat(cr.result()).isEqualTo(CommandResult.SENT);
    }

    @Test
    void synchronousExceptionSendsFailedResponse() throws Exception {
        var throwingDispatcher = new ThrowingDispatcher();
        var client = new BridgeCloudClient(mapper, throwingDispatcher);

        var cmd = new BridgeMessage.Command(
                "tenant-1", Instant.now(), "corr-3",
                new DeviceCommand("device-1", "turn_on", Map.of(), "test", "corr-3"));

        client.handleCommand(cmd, mockConnection);

        assertThat(sentMessages).hasSize(1);
        var cr = (BridgeMessage.CommandResponse) mapper.readValue(sentMessages.get(0), BridgeMessage.class);
        assertThat(cr.result()).isEqualTo(CommandResult.FAILED);
        assertThat(cr.correlationId()).isEqualTo("corr-3");
    }

    private static WebSocketClientConnection createMockConnection(List<String> sentMessages) {
        return (WebSocketClientConnection) java.lang.reflect.Proxy.newProxyInstance(
                WebSocketClientConnection.class.getClassLoader(),
                new Class[]{WebSocketClientConnection.class},
                (proxy, method, args) -> {
                    if ("sendTextAndAwait".equals(method.getName())) {
                        sentMessages.add((String) args[0]);
                    }
                    return null;
                });
    }

    private static class EmptyRegistry implements io.casehub.iot.api.spi.DeviceRegistry {
        @Override public java.util.Optional<io.casehub.iot.api.DeviceEntity> findById(String id) { return java.util.Optional.empty(); }
        @Override public <T extends io.casehub.iot.api.DeviceEntity> List<T> findByClass(Class<T> c) { return List.of(); }
        @Override public List<io.casehub.iot.api.DeviceEntity> findByTenancyId(String t) { return List.of(); }
        @Override public List<io.casehub.iot.api.DeviceEntity> findAll() { return List.of(); }
        @Override public Uni<Void> refresh() { return Uni.createFrom().voidItem(); }
    }

    private static class StubDispatcher extends BridgeCommandDispatcher {
        private final CommandResult result;
        StubDispatcher(CommandResult result) {
            super(List.of(), new EmptyRegistry());
            this.result = result;
        }
        @Override public Uni<CommandResult> dispatch(DeviceCommand command) {
            return Uni.createFrom().item(result);
        }
    }

    private static class ThrowingDispatcher extends BridgeCommandDispatcher {
        ThrowingDispatcher() { super(List.of(), new EmptyRegistry()); }
        @Override public Uni<CommandResult> dispatch(DeviceCommand command) {
            throw new RuntimeException("Synchronous dispatch failure");
        }
    }
}
