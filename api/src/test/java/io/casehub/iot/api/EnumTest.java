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
    void thermostatModeHasSixValues() {
        assertThat(ThermostatMode.values()).hasSize(6);
        assertThat(ThermostatMode.values()).containsExactly(
            ThermostatMode.HEAT, ThermostatMode.COOL, ThermostatMode.AUTO,
            ThermostatMode.OFF, ThermostatMode.FAN_ONLY, ThermostatMode.DRY);
    }

    @Test
    void sensorTypeHasEightValues() {
        assertThat(SensorType.values()).hasSize(8);
        assertThat(SensorType.valueOf("GENERIC")).isEqualTo(SensorType.GENERIC);
        assertThat(SensorType.valueOf("CO")).isNotEqualTo(SensorType.CO2);
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
