package io.casehub.iot.bridge.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.casehub.iot.api.CommandResult;
import io.casehub.iot.api.bridge.BridgeAuditEvent;
import jakarta.enterprise.event.Event;
import io.casehub.iot.api.DeviceClass;
import io.casehub.iot.api.DeviceCommand;
import io.casehub.iot.api.DeviceEntity;
import io.casehub.iot.api.LightDevice;
import io.casehub.iot.api.ProviderStatus;
import io.casehub.iot.api.StateChangeEvent;
import io.casehub.iot.api.SwitchDevice;
import io.casehub.iot.api.bridge.BridgeMessage;
import io.casehub.iot.testing.Fixtures;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class BridgeDeviceProviderTest {

    private static ObjectMapper mapper;
    private static DeviceIdNamespacer namespacer;
    private static final BridgeServerConfig TEST_CONFIG = new BridgeServerConfig() {
        @Override
        public int commandTimeoutSeconds() { return 1; }
    };

    private BridgeConnectionRegistry registry;
    private BridgeDeviceProvider provider;

    @BeforeAll
    static void initShared() {
        mapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .build();
        namespacer = new DeviceIdNamespacer(mapper);
    }

    @BeforeEach
    void setUp() {
        registry = new BridgeConnectionRegistry();
        Event<BridgeAuditEvent> mockAuditEvents = mockAuditEventEmitter();
        provider = new BridgeDeviceProvider(namespacer, registry, mapper, TEST_CONFIG, mockAuditEvents);
    }

    // --- Snapshot and status tests (existing) ---

    @Test
    void providerIdIsBridge() {
        assertThat(provider.providerId()).isEqualTo("bridge");
    }

    @Test
    void snapshotPopulatesDeviceMap() {
        SwitchDevice sw = Fixtures.hallwaySwitch();
        LightDevice light = Fixtures.livingRoomLight();

        provider.onSnapshot("site-a", List.of(sw, light));

        List<DeviceEntity> discovered = provider.discover().await().indefinitely();
        assertThat(discovered).hasSize(2);
        assertThat(discovered).extracting(DeviceEntity::deviceId)
                .containsExactlyInAnyOrder("site-a/switch-hallway-1", "site-a/light-living-1");
    }

    @Test
    void snapshotDiffDetectsStateChange() {
        SwitchDevice switchOff = Fixtures.hallwaySwitch();
        provider.onSnapshot("site-a", List.of(switchOff));

        SwitchDevice switchOn = SwitchDevice.builder()
                .deviceId("switch-hallway-1").deviceClass(DeviceClass.SWITCH)
                .label("Hallway Switch").available(true).lastUpdated(Fixtures.EPOCH)
                .tenancyId(Fixtures.DEFAULT_TENANT).providerId("test").on(true).build();

        List<StateChangeEvent> events = provider.onSnapshot("site-a", List.of(switchOn));

        assertThat(events).hasSize(1);
        StateChangeEvent event = events.get(0);
        assertThat(event.before()).isNotNull();
        assertThat(event.before().deviceId()).isEqualTo("site-a/switch-hallway-1");
        assertThat(event.after().deviceId()).isEqualTo("site-a/switch-hallway-1");
        assertThat(event.changedCapabilities()).contains(SwitchDevice.CAP_ON);
        assertThat(event.providerId()).isEqualTo("bridge");
    }

    @Test
    void snapshotDiffDetectsNewDevice() {
        SwitchDevice sw = Fixtures.hallwaySwitch();
        provider.onSnapshot("site-a", List.of(sw));

        LightDevice light = Fixtures.livingRoomLight();
        List<StateChangeEvent> events = provider.onSnapshot("site-a", List.of(sw, light));

        List<StateChangeEvent> newDeviceEvents = events.stream()
                .filter(e -> e.after().deviceId().equals("site-a/light-living-1"))
                .toList();
        assertThat(newDeviceEvents).hasSize(1);
        StateChangeEvent newEvent = newDeviceEvents.get(0);
        assertThat(newEvent.before()).isNull();
        assertThat(newEvent.changedCapabilities()).containsAll(newEvent.after().capabilities().keySet());
        assertThat(newEvent.providerId()).isEqualTo("bridge");
    }

    @Test
    void snapshotDiffDetectsRemovedDevice() {
        SwitchDevice sw = Fixtures.hallwaySwitch();
        LightDevice light = Fixtures.livingRoomLight();
        provider.onSnapshot("site-a", List.of(sw, light));

        List<StateChangeEvent> events = provider.onSnapshot("site-a", List.of(sw));

        List<StateChangeEvent> removedEvents = events.stream()
                .filter(e -> e.after().deviceId().equals("site-a/light-living-1"))
                .toList();
        assertThat(removedEvents).hasSize(1);
        StateChangeEvent removed = removedEvents.get(0);
        assertThat(removed.after().available()).isFalse();
        assertThat(removed.changedCapabilities()).contains(DeviceEntity.CAP_AVAILABLE);
        assertThat(removed.providerId()).isEqualTo("bridge");
    }

    @Test
    void statusDisconnectedWhenNoAgents() {
        assertThat(provider.status()).isEqualTo(ProviderStatus.DISCONNECTED);
    }

    @Test
    void statusConnectedWhenAllAgentsConnected() {
        registry.register("site-a", mockConnection());
        registry.register("site-b", mockConnection());

        assertThat(provider.status()).isEqualTo(ProviderStatus.CONNECTED);
    }

    @Test
    void statusConnectingWhenPartiallyConnected() {
        registry.register("site-a", mockConnection());
        registry.register("site-b", mockConnection());

        registry.unregister("site-b");

        assertThat(provider.status()).isEqualTo(ProviderStatus.CONNECTING);
    }

    @Test
    void snapshotNoEventsWhenUnchanged() {
        SwitchDevice sw = Fixtures.hallwaySwitch();
        provider.onSnapshot("site-a", List.of(sw));

        List<StateChangeEvent> events = provider.onSnapshot("site-a", List.of(sw));

        assertThat(events).isEmpty();
    }

    @Test
    void multiTenancyIsolation() {
        SwitchDevice sw = Fixtures.hallwaySwitch();
        LightDevice light = Fixtures.livingRoomLight();

        provider.onSnapshot("site-a", List.of(sw));
        provider.onSnapshot("site-b", List.of(light));

        List<DeviceEntity> discovered = provider.discover().await().indefinitely();
        assertThat(discovered).hasSize(2);
        assertThat(discovered).extracting(DeviceEntity::deviceId)
                .containsExactlyInAnyOrder("site-a/switch-hallway-1", "site-b/light-living-1");
    }

    // --- Dispatch tests (new) ---

    @Test
    void dispatchSendsCommandAndCorrelatesResponse() {
        List<String> sent = new ArrayList<>();
        registry.register("site-a", mockSendableConnection(sent, Uni.createFrom().voidItem()));

        DeviceCommand cmd = DeviceCommand.turnOn("site-a/switch-1", Map.of(), "test", "corr-1");

        AtomicReference<CommandResult> resultRef = new AtomicReference<>();
        provider.dispatch(cmd).subscribe().with(resultRef::set);

        assertThat(sent).hasSize(1);

        BridgeMessage message = deserialize(sent.get(0));
        assertThat(message).isInstanceOf(BridgeMessage.Command.class);
        BridgeMessage.Command cmdMsg = (BridgeMessage.Command) message;
        assertThat(cmdMsg.tenancyId()).isEqualTo("site-a");
        assertThat(cmdMsg.correlationId()).isEqualTo("corr-1");
        assertThat(cmdMsg.command().targetDeviceId()).isEqualTo("switch-1");
        assertThat(cmdMsg.command().action()).isEqualTo("turn_on");

        provider.completeCommand("site-a", "corr-1", CommandResult.SENT);

        assertThat(resultRef.get()).isEqualTo(CommandResult.SENT);
    }

    @Test
    void dispatchFailsWhenAgentNotConnected() {
        DeviceCommand cmd = DeviceCommand.turnOn("site-a/switch-hallway-1", Map.of(), "test", "corr-1");

        CommandResult result = provider.dispatch(cmd).await().indefinitely();

        assertThat(result).isEqualTo(CommandResult.FAILED);
    }

    @Test
    void dispatchTimesOutWhenNoResponse() {
        registry.register("site-a", mockSendableConnection(new ArrayList<>(), Uni.createFrom().voidItem()));

        DeviceCommand cmd = DeviceCommand.turnOn("site-a/switch-1", Map.of(), "test", "corr-1");

        CommandResult result = provider.dispatch(cmd).await().atMost(Duration.ofSeconds(5));

        assertThat(result).isEqualTo(CommandResult.TIMEOUT);
    }

    @Test
    void dispatchGeneratesCorrelationIdWhenNull() {
        List<String> sent = new ArrayList<>();
        registry.register("site-a", mockSendableConnection(sent, Uni.createFrom().voidItem()));

        DeviceCommand cmd = new DeviceCommand("site-a/switch-1", "turn_on", Map.of(), "test", null);

        provider.dispatch(cmd).subscribe().with(r -> {});

        assertThat(sent).hasSize(1);
        BridgeMessage.Command cmdMsg = (BridgeMessage.Command) deserialize(sent.get(0));
        assertThat(cmdMsg.correlationId()).isNotNull().isNotBlank();
        assertThat(cmdMsg.command().correlationId()).isEqualTo(cmdMsg.correlationId());
    }

    @Test
    void dispatchFailsImmediatelyOnSendFailure() {
        Uni<Void> failingSend = Uni.createFrom().failure(new RuntimeException("connection lost"));
        registry.register("site-a", mockSendableConnection(new ArrayList<>(), failingSend));

        DeviceCommand cmd = DeviceCommand.turnOn("site-a/switch-1", Map.of(), "test", "corr-1");

        CommandResult result = provider.dispatch(cmd).await().atMost(Duration.ofSeconds(2));

        assertThat(result).isEqualTo(CommandResult.FAILED);
    }

    @Test
    void concurrentDispatchesResolveCorrectly() {
        registry.register("site-a", mockSendableConnection(new ArrayList<>(), Uni.createFrom().voidItem()));

        DeviceCommand cmd1 = DeviceCommand.turnOn("site-a/switch-1", Map.of(), "test", "corr-1");
        DeviceCommand cmd2 = DeviceCommand.turnOff("site-a/switch-2", "test", "corr-2");

        AtomicReference<CommandResult> result1 = new AtomicReference<>();
        AtomicReference<CommandResult> result2 = new AtomicReference<>();
        provider.dispatch(cmd1).subscribe().with(result1::set);
        provider.dispatch(cmd2).subscribe().with(result2::set);

        provider.completeCommand("site-a", "corr-2", CommandResult.SENT);
        provider.completeCommand("site-a", "corr-1", CommandResult.FAILED);

        assertThat(result1.get()).isEqualTo(CommandResult.FAILED);
        assertThat(result2.get()).isEqualTo(CommandResult.SENT);
    }

    @Test
    void lateResponseAfterTimeoutIsNoOp() {
        registry.register("site-a", mockSendableConnection(new ArrayList<>(), Uni.createFrom().voidItem()));

        DeviceCommand cmd = DeviceCommand.turnOn("site-a/switch-1", Map.of(), "test", "corr-1");

        CommandResult result = provider.dispatch(cmd).await().atMost(Duration.ofSeconds(5));
        assertThat(result).isEqualTo(CommandResult.TIMEOUT);

        provider.completeCommand("site-a", "corr-1", CommandResult.SENT);
    }

    @Test
    void crossTenancyResponseIsNoOp() {
        registry.register("site-a", mockSendableConnection(new ArrayList<>(), Uni.createFrom().voidItem()));

        DeviceCommand cmd = DeviceCommand.turnOn("site-a/switch-1", Map.of(), "test", "corr-1");

        AtomicReference<CommandResult> resultRef = new AtomicReference<>();
        provider.dispatch(cmd).subscribe().with(resultRef::set);

        provider.completeCommand("site-b", "corr-1", CommandResult.SENT);

        assertThat(resultRef.get()).isNull();
    }

    // --- Helpers ---

    private BridgeMessage deserialize(String json) {
        try {
            return mapper.readValue(json, BridgeMessage.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize BridgeMessage", e);
        }
    }

    private static WebSocketConnection mockConnection() {
        return (WebSocketConnection) Proxy.newProxyInstance(
                WebSocketConnection.class.getClassLoader(),
                new Class[]{WebSocketConnection.class},
                (proxy, method, args) -> null);
    }

    private static WebSocketConnection mockSendableConnection(List<String> sent, Uni<Void> sendResult) {
        return (WebSocketConnection) Proxy.newProxyInstance(
                WebSocketConnection.class.getClassLoader(),
                new Class[]{WebSocketConnection.class},
                (proxy, method, args) -> {
                    if ("sendText".equals(method.getName()) && args != null && args.length == 1) {
                        sent.add(args[0].toString());
                        return sendResult;
                    }
                    return null;
                });
    }

    @SuppressWarnings("unchecked")
    private static Event<BridgeAuditEvent> mockAuditEventEmitter() {
        return (Event<BridgeAuditEvent>) Proxy.newProxyInstance(
                Event.class.getClassLoader(),
                new Class[]{Event.class},
                (proxy, method, args) -> null);
    }
}
