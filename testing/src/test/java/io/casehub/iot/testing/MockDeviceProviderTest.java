package io.casehub.iot.testing;

import io.casehub.iot.api.CommandResult;
import io.casehub.iot.api.DeviceClass;
import io.casehub.iot.api.DeviceCommand;
import io.casehub.iot.api.ProviderStatus;
import io.casehub.iot.api.SwitchDevice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

class MockDeviceProviderTest {

    static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    MockDeviceProvider provider;

    @BeforeEach
    void setUp() {
        provider = new MockDeviceProvider("test");
    }

    private SwitchDevice sw(String id) {
        return SwitchDevice.builder()
            .deviceId(id).deviceClass(DeviceClass.SWITCH).label("S")
            .available(true).lastUpdated(NOW).tenancyId("t1").on(false).build();
    }

    @Test
    void providerIdReturned() {
        assertThat(provider.providerId()).isEqualTo("test");
    }

    @Test
    void discoverReturnsAddedDevices() {
        provider.addDevice(sw("sw1"));
        var devices = provider.discover().await().indefinitely();
        assertThat(devices).hasSize(1);
        assertThat(devices.get(0).deviceId()).isEqualTo("sw1");
    }

    @Test
    void discoverReturnsEmptyWhenNothingAdded() {
        assertThat(provider.discover().await().indefinitely()).isEmpty();
    }

    @Test
    void addDeviceOverwritesExistingByDeviceId() {
        provider.addDevice(sw("sw1"));
        var updated = sw("sw1").toBuilder().available(false).build();
        provider.addDevice(updated);
        var devices = provider.discover().await().indefinitely();
        assertThat(devices).hasSize(1);
        assertThat(devices.get(0).available()).isFalse();
    }

    @Test
    void removeDeviceRemovesFromDiscovery() {
        provider.addDevice(sw("sw1"));
        provider.removeDevice("sw1");
        assertThat(provider.discover().await().indefinitely()).isEmpty();
    }

    @Test
    void clearRemovesAllDevices() {
        provider.addDevice(sw("sw1"));
        provider.addDevice(sw("sw2"));
        provider.clear();
        assertThat(provider.discover().await().indefinitely()).isEmpty();
    }

    @Test
    void dispatchRecordsCommand() {
        var cmd = DeviceCommand.turnOff("sw1", "actor", "corr");
        provider.dispatch(cmd).await().indefinitely();
        assertThat(provider.dispatchedCommands()).containsExactly(cmd);
    }

    @Test
    void dispatchDefaultResultIsSent() {
        assertThat(provider.dispatch(DeviceCommand.turnOff("sw1", "a", "c")).await().indefinitely())
            .isEqualTo(CommandResult.SENT);
    }

    @Test
    void dispatchReturnsConfiguredResult() {
        provider.setDispatchResult(CommandResult.FAILED);
        assertThat(provider.dispatch(DeviceCommand.turnOff("sw1", "a", "c")).await().indefinitely())
            .isEqualTo(CommandResult.FAILED);
    }

    @Test
    void statusDefaultIsConnected() {
        assertThat(provider.status()).isEqualTo(ProviderStatus.CONNECTED);
    }

    @Test
    void statusReturnsConfiguredStatus() {
        provider.setStatus(ProviderStatus.DISCONNECTED);
        assertThat(provider.status()).isEqualTo(ProviderStatus.DISCONNECTED);
    }

    @Test
    void clearDispatchedCommandsClearsLog() {
        provider.dispatch(DeviceCommand.turnOff("sw1", "a", "c")).await().indefinitely();
        provider.clearDispatchedCommands();
        assertThat(provider.dispatchedCommands()).isEmpty();
    }
}
