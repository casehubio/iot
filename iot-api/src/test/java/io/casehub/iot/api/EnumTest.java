package io.casehub.iot.api;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class EnumTest {

    @Test
    void deviceClassHasTenValues() {
        assertThat(DeviceClass.values()).hasSize(10);
        assertThat(DeviceClass.valueOf("SWITCH")).isEqualTo(DeviceClass.SWITCH);
        assertThat(DeviceClass.valueOf("PRESENCE_SENSOR")).isEqualTo(DeviceClass.PRESENCE_SENSOR);
    }

    @Test
    void thermostatModeHasFiveValues() {
        assertThat(ThermostatMode.values()).hasSize(5);
        assertThat(ThermostatMode.values()).containsExactly(
            ThermostatMode.HEAT, ThermostatMode.COOL, ThermostatMode.AUTO,
            ThermostatMode.OFF, ThermostatMode.FAN_ONLY);
    }

    @Test
    void sensorTypeHasSevenValues() {
        assertThat(SensorType.values()).hasSize(7);
        assertThat(SensorType.valueOf("GENERIC")).isEqualTo(SensorType.GENERIC);
    }

    @Test
    void commandResultHasThreeValues() {
        assertThat(CommandResult.values()).containsExactly(
            CommandResult.SENT, CommandResult.FAILED, CommandResult.TIMEOUT);
    }

    @Test
    void providerStatusHasThreeValues() {
        assertThat(ProviderStatus.values()).containsExactly(
            ProviderStatus.CONNECTED, ProviderStatus.CONNECTING, ProviderStatus.DISCONNECTED);
    }
}
