package io.casehub.iot.api;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class EventCommandTest {

    private static final Instant NOW = Instant.parse("2026-06-07T10:00:00Z");

    private SwitchDevice switchDevice(String id, boolean on) {
        return SwitchDevice.builder()
            .deviceId(id).deviceClass(DeviceClass.SWITCH).label("Switch")
            .available(true).lastUpdated(NOW).tenancyId("t1").on(on).build();
    }

    @Test
    void stateChangeEventFields() {
        var before = switchDevice("sw1", false);
        var after = switchDevice("sw1", true);
        var event = new StateChangeEvent(before, after,
            Set.of(SwitchDevice.CAP_ON), NOW, "homeassistant");
        assertThat(event.before()).isEqualTo(before);
        assertThat(event.after()).isEqualTo(after);
        assertThat(event.changedCapabilities()).containsExactly("isOn");
        assertThat(event.providerId()).isEqualTo("homeassistant");
    }

    @Test
    void providerStatusEventFields() {
        var event = new ProviderStatusEvent("ha",
            ProviderStatus.DISCONNECTED, ProviderStatus.CONNECTED);
        assertThat(event.providerId()).isEqualTo("ha");
        assertThat(event.previousStatus()).isEqualTo(ProviderStatus.DISCONNECTED);
        assertThat(event.currentStatus()).isEqualTo(ProviderStatus.CONNECTED);
    }

    @Test
    void deviceCommandActionConstants() {
        assertThat(DeviceCommand.ACTION_TURN_ON).isEqualTo("turn_on");
        assertThat(DeviceCommand.ACTION_TURN_OFF).isEqualTo("turn_off");
        assertThat(DeviceCommand.ACTION_SET_TEMPERATURE).isEqualTo("set_temperature");
        assertThat(DeviceCommand.ACTION_LOCK).isEqualTo("lock");
        assertThat(DeviceCommand.ACTION_UNLOCK).isEqualTo("unlock");
        assertThat(DeviceCommand.ACTION_SET_POSITION).isEqualTo("set_position");
        assertThat(DeviceCommand.ACTION_SET_VOLUME).isEqualTo("set_volume");
    }

    @Test
    void deviceCommandGenericConstructor() {
        var cmd = new DeviceCommand("sw1", "custom_action",
            Map.of("key", "value"), "actor1", "corr1");
        assertThat(cmd.targetDeviceId()).isEqualTo("sw1");
        assertThat(cmd.action()).isEqualTo("custom_action");
        assertThat(cmd.parameters()).containsEntry("key", "value");
        assertThat(cmd.dispatchedBy()).isEqualTo("actor1");
        assertThat(cmd.correlationId()).isEqualTo("corr1");
    }

    @Test
    void deviceCommandTurnOnFactory() {
        var cmd = DeviceCommand.turnOn("l1", Map.of("brightness", 200), "actor", "corr");
        assertThat(cmd.action()).isEqualTo("turn_on");
        assertThat(cmd.targetDeviceId()).isEqualTo("l1");
        assertThat(cmd.parameters()).containsEntry("brightness", 200);
    }

    @Test
    void deviceCommandTurnOffFactory() {
        var cmd = DeviceCommand.turnOff("sw1", "actor", "corr");
        assertThat(cmd.action()).isEqualTo("turn_off");
        assertThat(cmd.parameters()).isEmpty();
    }

    @Test
    void deviceCommandSetTemperatureFactory() {
        var target = new Temperature(BigDecimal.valueOf(22), Temperature.TemperatureUnit.CELSIUS);
        var cmd = DeviceCommand.setTemperature("th1", target, "actor", "corr");
        assertThat(cmd.action()).isEqualTo("set_temperature");
        assertThat(cmd.parameters()).containsEntry("temperature", BigDecimal.valueOf(22));
        assertThat(cmd.parameters()).containsEntry("unit", "CELSIUS");
    }

    @Test
    void deviceCommandLockFactory() {
        var cmd = DeviceCommand.lock("lk1", "actor", "corr");
        assertThat(cmd.action()).isEqualTo("lock");
    }

    @Test
    void deviceCommandUnlockFactory() {
        var cmd = DeviceCommand.unlock("lk1", "actor", "corr");
        assertThat(cmd.action()).isEqualTo("unlock");
    }

    @Test
    void deviceCommandSetPositionFactory() {
        var cmd = DeviceCommand.setPosition("cv1", 50, "actor", "corr");
        assertThat(cmd.action()).isEqualTo("set_position");
        assertThat(cmd.parameters()).containsEntry("position", 50);
    }

    @Test
    void deviceCommandSetVolumeFactory() {
        var cmd = DeviceCommand.setVolume("mp1", 80, "actor", "corr");
        assertThat(cmd.action()).isEqualTo("set_volume");
        assertThat(cmd.parameters()).containsEntry("volume", 80);
    }
}
