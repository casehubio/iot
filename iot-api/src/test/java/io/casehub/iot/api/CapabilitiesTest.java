package io.casehub.iot.api;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

class CapabilitiesTest {

    static final Instant NOW = Instant.parse("2026-06-07T10:00:00Z");

    // Helper — minimal device for testing DeviceEntity base behavior
    private SwitchDevice sw(boolean on) {
        return SwitchDevice.builder()
            .deviceId("sw1").deviceClass(DeviceClass.SWITCH).label("Switch")
            .available(true).lastUpdated(NOW).tenancyId("t1").on(on).build();
    }

    @Test
    void baseCapabilitiesContainsCAPAVAILABLE() {
        var device = sw(false);
        assertThat(device.capabilities()).containsKey(DeviceEntity.CAP_AVAILABLE);
        assertThat(device.capabilities().get(DeviceEntity.CAP_AVAILABLE)).isEqualTo(true);
    }

    @Test
    void capabilitiesAllocatesFreshMapEachCall() {
        var device = sw(false);
        var caps1 = device.capabilities();
        caps1.put("injected", "value");
        assertThat(device.capabilities()).doesNotContainKey("injected");
    }

    @Test
    void unavailableDeviceCapabilitiesShowsFalse() {
        var device = SwitchDevice.builder()
            .deviceId("sw1").deviceClass(DeviceClass.SWITCH).label("Switch")
            .available(false).lastUpdated(NOW).tenancyId("t1").on(false).build();
        assertThat(device.capabilities().get(DeviceEntity.CAP_AVAILABLE)).isEqualTo(false);
    }

    @Test
    void switchDeviceCapabilitiesContainsOnAndAvailable() {
        var device = SwitchDevice.builder()
            .deviceId("sw1").deviceClass(DeviceClass.SWITCH).label("Switch")
            .available(true).lastUpdated(NOW).tenancyId("t1").on(true).build();
        var caps = device.capabilities();
        assertThat(caps).containsEntry(DeviceEntity.CAP_AVAILABLE, true);
        assertThat(caps).containsEntry(SwitchDevice.CAP_ON, true);
        assertThat(caps).hasSize(2);
    }

    @Test
    void switchDeviceCapabilitiesReflectsOffState() {
        var device = SwitchDevice.builder()
            .deviceId("sw1").deviceClass(DeviceClass.SWITCH).label("Switch")
            .available(true).lastUpdated(NOW).tenancyId("t1").on(false).build();
        assertThat(device.capabilities()).containsEntry(SwitchDevice.CAP_ON, false);
    }

    @Test
    void lightDeviceCapabilitiesWithAllFields() {
        var device = LightDevice.builder()
            .deviceId("l1").deviceClass(DeviceClass.LIGHT).label("Light")
            .available(true).lastUpdated(NOW).tenancyId("t1")
            .on(true).brightness(200).colorTemp(4000).build();
        var caps = device.capabilities();
        assertThat(caps).containsEntry(DeviceEntity.CAP_AVAILABLE, true);
        assertThat(caps).containsEntry(LightDevice.CAP_ON, true);
        assertThat(caps).containsEntry(LightDevice.CAP_BRIGHTNESS, 200);
        assertThat(caps).containsEntry(LightDevice.CAP_COLOR_TEMP, 4000);
        assertThat(caps).hasSize(4);
    }

    @Test
    void lightDeviceCapabilitiesNullOptionalFieldsIncludedAsNull() {
        var device = LightDevice.builder()
            .deviceId("l1").deviceClass(DeviceClass.LIGHT).label("Light")
            .available(true).lastUpdated(NOW).tenancyId("t1").on(false).build();
        var caps = device.capabilities();
        assertThat(caps).containsKey(LightDevice.CAP_BRIGHTNESS);
        assertThat(caps.get(LightDevice.CAP_BRIGHTNESS)).isNull();
        assertThat(caps).containsKey(LightDevice.CAP_COLOR_TEMP);
        assertThat(caps.get(LightDevice.CAP_COLOR_TEMP)).isNull();
        assertThat(caps).hasSize(4);
    }

    @Test
    void thermostatDeviceCapabilities() {
        var current = new Temperature(new BigDecimal("21"), Temperature.TemperatureUnit.CELSIUS);
        var target = new Temperature(new BigDecimal("22"), Temperature.TemperatureUnit.CELSIUS);
        var device = ThermostatDevice.builder()
            .deviceId("th1").deviceClass(DeviceClass.THERMOSTAT).label("Thermostat")
            .available(true).lastUpdated(NOW).tenancyId("t1")
            .currentTemperature(current).targetTemperature(target).mode(ThermostatMode.HEAT).build();
        var caps = device.capabilities();
        assertThat(caps).containsEntry(DeviceEntity.CAP_AVAILABLE, true);
        assertThat(caps).containsEntry(ThermostatDevice.CAP_CURRENT_TEMPERATURE, current);
        assertThat(caps).containsEntry(ThermostatDevice.CAP_TARGET_TEMPERATURE, target);
        assertThat(caps).containsEntry(ThermostatDevice.CAP_MODE, ThermostatMode.HEAT);
        assertThat(caps).hasSize(4);
    }

    @Test
    void sensorDeviceCapabilitiesExcludesUnit() {
        var device = SensorDevice.builder()
            .deviceId("s1").deviceClass(DeviceClass.SENSOR).label("Sensor")
            .available(true).lastUpdated(NOW).tenancyId("t1")
            .sensorType(SensorType.TEMPERATURE)
            .numericValue(new BigDecimal("21.5")).unit("C").build();
        var caps = device.capabilities();
        assertThat(caps).containsEntry(DeviceEntity.CAP_AVAILABLE, true);
        assertThat(caps).containsKey(SensorDevice.CAP_NUMERIC_VALUE);
        assertThat(caps).containsKey(SensorDevice.CAP_BINARY_VALUE);
        assertThat(caps).doesNotContainKey("unit");
        assertThat(caps).hasSize(3);
    }

    @Test
    void sensorDeviceCapabilitiesNullValues() {
        var device = SensorDevice.builder()
            .deviceId("s1").deviceClass(DeviceClass.SENSOR).label("Sensor")
            .available(true).lastUpdated(NOW).tenancyId("t1")
            .sensorType(SensorType.MOTION).build();
        var caps = device.capabilities();
        assertThat(caps.get(SensorDevice.CAP_NUMERIC_VALUE)).isNull();
        assertThat(caps.get(SensorDevice.CAP_BINARY_VALUE)).isNull();
    }
}
