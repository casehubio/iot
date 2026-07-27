package io.casehub.iot.bridge.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.iot.api.DeviceClass;
import io.casehub.iot.api.DeviceEntity;
import io.casehub.iot.api.LightDevice;
import io.casehub.iot.api.ProviderStatus;
import io.casehub.iot.api.StateChangeEvent;
import io.casehub.iot.api.SwitchDevice;
import io.casehub.iot.api.ThermostatDevice;
import io.casehub.iot.testing.Fixtures;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.inject.Inject;

import java.lang.reflect.Proxy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class BridgeIntegrationTest {

    @Inject BridgeDeviceProvider provider;
    @Inject BridgeConnectionRegistry registry;

    @BeforeEach
    void resetState() {
        // Clear device maps by sending empty snapshots for any tenancies used in prior tests.
        // This avoids cross-test pollution since BridgeDeviceProvider is @ApplicationScoped.
        provider.onSnapshot("test-tenant", List.of());
        provider.onSnapshot("site-a", List.of());
        provider.onSnapshot("site-b", List.of());
    }

    // ── Snapshot → discover ────────────────────────────────────────────

    @Test
    void snapshotPopulatesProviderDeviceMap() {
        SwitchDevice sw = Fixtures.hallwaySwitch();
        LightDevice light = Fixtures.livingRoomLight();
        ThermostatDevice thermo = Fixtures.livingRoomThermostat();

        provider.onSnapshot("test-tenant", List.of(sw, light, thermo));

        List<DeviceEntity> discovered = provider.discover();
        assertThat(discovered).hasSize(3);
        assertThat(discovered).extracting(DeviceEntity::deviceId)
                .containsExactlyInAnyOrder(
                        "test-tenant/switch-hallway-1",
                        "test-tenant/light-living-1",
                        "test-tenant/thermostat-living-1");
    }

    @Test
    void snapshotNamespacesPreserveDeviceType() {
        SwitchDevice sw = Fixtures.hallwaySwitch();
        ThermostatDevice thermo = Fixtures.livingRoomThermostat();

        provider.onSnapshot("test-tenant", List.of(sw, thermo));

        List<DeviceEntity> discovered = provider.discover();
        assertThat(discovered).anySatisfy(d -> {
            assertThat(d).isInstanceOf(SwitchDevice.class);
            assertThat(d.deviceClass()).isEqualTo(DeviceClass.SWITCH);
        });
        assertThat(discovered).anySatisfy(d -> {
            assertThat(d).isInstanceOf(ThermostatDevice.class);
            assertThat(d.deviceClass()).isEqualTo(DeviceClass.THERMOSTAT);
        });
    }

    // ── State change pass-through ──────────────────────────────────────

    @Test
    void stateChangeUpdatesDeviceMap() {
        SwitchDevice sw = Fixtures.hallwaySwitch();
        provider.onSnapshot("test-tenant", List.of(sw));

        // Simulate a live state change: switch turned on
        SwitchDevice switchOn = sw.toBuilder().on(true).build();
        StateChangeEvent event = new StateChangeEvent(
                sw, switchOn,
                StateChangeEvent.deriveChangedCapabilities(sw, switchOn),
                Fixtures.EPOCH, "bridge");
        provider.onStateChange(event, "test-tenant");

        List<DeviceEntity> discovered = provider.discover();
        assertThat(discovered).hasSize(1);

        SwitchDevice updated = (SwitchDevice) discovered.stream()
                .filter(d -> d.deviceId().equals("test-tenant/switch-hallway-1"))
                .findFirst().orElseThrow();
        assertThat(updated.isOn()).isTrue();
    }

    // ── Snapshot diff events ───────────────────────────────────────────

    @Test
    void snapshotDiffFiresEventsForChangedDevices() {
        SwitchDevice switchOff = Fixtures.hallwaySwitch();
        provider.onSnapshot("site-a", List.of(switchOff));

        // Second snapshot: switch is now on
        SwitchDevice switchOn = switchOff.toBuilder().on(true).build();
        List<StateChangeEvent> events = provider.onSnapshot("site-a", List.of(switchOn));

        assertThat(events).hasSize(1);
        StateChangeEvent event = events.get(0);
        assertThat(event.before()).isNotNull();
        assertThat(event.after().deviceId()).isEqualTo("site-a/switch-hallway-1");
        assertThat(event.changedCapabilities()).contains(SwitchDevice.CAP_ON);
    }

    @Test
    void snapshotDiffHandlesDeviceRemoval() {
        SwitchDevice sw = Fixtures.hallwaySwitch();
        LightDevice light = Fixtures.livingRoomLight();
        provider.onSnapshot("site-a", List.of(sw, light));

        // Second snapshot: light removed
        List<StateChangeEvent> events = provider.onSnapshot("site-a", List.of(sw));

        List<StateChangeEvent> removals = events.stream()
                .filter(e -> e.after().deviceId().equals("site-a/light-living-1"))
                .toList();
        assertThat(removals).hasSize(1);
        StateChangeEvent removed = removals.get(0);
        assertThat(removed.after().available()).isFalse();
        assertThat(removed.changedCapabilities()).contains(DeviceEntity.CAP_AVAILABLE);
    }

    @Test
    void snapshotDiffDetectsNewDevice() {
        SwitchDevice sw = Fixtures.hallwaySwitch();
        provider.onSnapshot("site-a", List.of(sw));

        // Second snapshot adds a light
        LightDevice light = Fixtures.livingRoomLight();
        List<StateChangeEvent> events = provider.onSnapshot("site-a", List.of(sw, light));

        List<StateChangeEvent> additions = events.stream()
                .filter(e -> e.before() == null)
                .toList();
        assertThat(additions).hasSize(1);
        assertThat(additions.get(0).after().deviceId()).isEqualTo("site-a/light-living-1");
        assertThat(additions.get(0).changedCapabilities())
                .containsAll(additions.get(0).after().capabilities().keySet());
    }

    // ── Provider status reflects connection registry ───────────────────
    //
    // BridgeConnectionRegistry.knownTenancies is a sticky set — once a tenancy
    // registers, it stays "known" even after unregister. isFullyConnected()
    // checks that ALL known tenancies have active sessions. Because the registry
    // is @ApplicationScoped (shared across tests), we use a single test that
    // exercises the full lifecycle in order: DISCONNECTED → CONNECTED → CONNECTING
    // → DISCONNECTED. This avoids order-dependent failures from accumulated
    // knownTenancies leaking between tests.

    @Test
    void providerStatusReflectsConnectionLifecycle() {
        // No agents → DISCONNECTED
        assertThat(provider.status()).isEqualTo(ProviderStatus.DISCONNECTED);
        assertThat(registry.hasAnyConnection()).isFalse();

        // Register one agent → CONNECTED (all known tenancies have sessions)
        registry.register("lifecycle-a", mockConnection());
        assertThat(provider.status()).isEqualTo(ProviderStatus.CONNECTED);
        assertThat(registry.hasAnyConnection()).isTrue();

        // Register a second agent, then disconnect it → CONNECTING
        // (lifecycle-b is now "known" but has no session)
        registry.register("lifecycle-b", mockConnection());
        assertThat(provider.status()).isEqualTo(ProviderStatus.CONNECTED);

        registry.unregister("lifecycle-b");
        assertThat(provider.status()).isEqualTo(ProviderStatus.CONNECTING);
        assertThat(registry.hasAnyConnection()).isTrue();

        // Unregister the remaining agent → DISCONNECTED
        registry.unregister("lifecycle-a");
        assertThat(provider.status()).isEqualTo(ProviderStatus.DISCONNECTED);
        assertThat(registry.hasAnyConnection()).isFalse();
    }

    private static WebSocketConnection mockConnection() {
        return (WebSocketConnection) Proxy.newProxyInstance(
                WebSocketConnection.class.getClassLoader(),
                new Class[]{WebSocketConnection.class},
                (proxy, method, args) -> null);
    }
}
