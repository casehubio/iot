package io.casehub.iot.api;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeriveChangedCapabilitiesTest {

    static final Instant NOW = Instant.parse("2026-06-07T10:00:00Z");

    private SwitchDevice sw(boolean on) {
        return SwitchDevice.builder()
            .deviceId("sw1").deviceClass(DeviceClass.SWITCH).label("S")
            .available(true).lastUpdated(NOW).tenancyId("t1").on(on).build();
    }

    @Test
    void noChangeProducesEmptySet() {
        var device = sw(true);
        assertThat(StateChangeEvent.deriveChangedCapabilities(device, device)).isEmpty();
    }

    @Test
    void singleFieldChangedDetectedCorrectly() {
        var before = sw(false);
        var after = sw(true);
        assertThat(StateChangeEvent.deriveChangedCapabilities(before, after))
            .containsExactly(SwitchDevice.CAP_ON);
    }

    @Test
    void availabilityChangeDetected() {
        var before = SwitchDevice.builder()
            .deviceId("sw1").deviceClass(DeviceClass.SWITCH).label("S")
            .available(true).lastUpdated(NOW).tenancyId("t1").on(false).build();
        var after = SwitchDevice.builder()
            .deviceId("sw1").deviceClass(DeviceClass.SWITCH).label("S")
            .available(false).lastUpdated(NOW).tenancyId("t1").on(false).build();
        assertThat(StateChangeEvent.deriveChangedCapabilities(before, after))
            .containsExactly(DeviceEntity.CAP_AVAILABLE);
    }

    @Test
    void nullToNonNullTransitionDetected() {
        var before = new LightDevice.Builder()
            .deviceId("l1").deviceClass(DeviceClass.LIGHT).label("L")
            .available(true).lastUpdated(NOW).tenancyId("t1").on(true).build();
        var after = new LightDevice.Builder()
            .deviceId("l1").deviceClass(DeviceClass.LIGHT).label("L")
            .available(true).lastUpdated(NOW).tenancyId("t1").on(true).brightness(200).build();
        assertThat(StateChangeEvent.deriveChangedCapabilities(before, after))
            .containsExactly(LightDevice.CAP_BRIGHTNESS);
    }

    @Test
    void nonNullToNullTransitionDetected() {
        var before = new LightDevice.Builder()
            .deviceId("l1").deviceClass(DeviceClass.LIGHT).label("L")
            .available(true).lastUpdated(NOW).tenancyId("t1").on(true).brightness(200).build();
        var after = new LightDevice.Builder()
            .deviceId("l1").deviceClass(DeviceClass.LIGHT).label("L")
            .available(true).lastUpdated(NOW).tenancyId("t1").on(true).build();
        assertThat(StateChangeEvent.deriveChangedCapabilities(before, after))
            .containsExactly(LightDevice.CAP_BRIGHTNESS);
    }

    @Test
    void multipleFieldChangesDetected() {
        var current = new Temperature(new BigDecimal("21"), Temperature.TemperatureUnit.CELSIUS);
        var newCurrent = new Temperature(new BigDecimal("22"), Temperature.TemperatureUnit.CELSIUS);
        var target = new Temperature(new BigDecimal("23"), Temperature.TemperatureUnit.CELSIUS);
        var before = new ThermostatDevice.Builder()
            .deviceId("th1").deviceClass(DeviceClass.THERMOSTAT).label("T")
            .available(true).lastUpdated(NOW).tenancyId("t1")
            .currentTemperature(current).targetTemperature(target).mode(ThermostatMode.HEAT).build();
        var after = new ThermostatDevice.Builder()
            .deviceId("th1").deviceClass(DeviceClass.THERMOSTAT).label("T")
            .available(true).lastUpdated(NOW).tenancyId("t1")
            .currentTemperature(newCurrent).targetTemperature(target).mode(ThermostatMode.COOL).build();
        var changed = StateChangeEvent.deriveChangedCapabilities(before, after);
        assertThat(changed).containsExactlyInAnyOrder(
            ThermostatDevice.CAP_CURRENT_TEMPERATURE, ThermostatDevice.CAP_MODE);
    }

    @Test
    void temperatureScaleInsensitiveComparison() {
        var t21 = new Temperature(new BigDecimal("21"), Temperature.TemperatureUnit.CELSIUS);
        var t21same = new Temperature(new BigDecimal("21.0"), Temperature.TemperatureUnit.CELSIUS);
        var target = new Temperature(new BigDecimal("22"), Temperature.TemperatureUnit.CELSIUS);
        var before = new ThermostatDevice.Builder()
            .deviceId("th1").deviceClass(DeviceClass.THERMOSTAT).label("T")
            .available(true).lastUpdated(NOW).tenancyId("t1")
            .currentTemperature(t21).targetTemperature(target).mode(ThermostatMode.HEAT).build();
        var after = new ThermostatDevice.Builder()
            .deviceId("th1").deviceClass(DeviceClass.THERMOSTAT).label("T")
            .available(true).lastUpdated(NOW).tenancyId("t1")
            .currentTemperature(t21same).targetTemperature(target).mode(ThermostatMode.HEAT).build();
        assertThat(StateChangeEvent.deriveChangedCapabilities(before, after)).isEmpty();
    }

    @Test
    void differentTypesThrowIllegalArgumentException() {
        var sw = SwitchDevice.builder()
            .deviceId("sw1").deviceClass(DeviceClass.SWITCH).label("S")
            .available(true).lastUpdated(NOW).tenancyId("t1").on(false).build();
        var light = new LightDevice.Builder()
            .deviceId("l1").deviceClass(DeviceClass.LIGHT).label("L")
            .available(true).lastUpdated(NOW).tenancyId("t1").on(false).build();
        assertThatThrownBy(() -> StateChangeEvent.deriveChangedCapabilities(sw, light))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("SwitchDevice")
            .hasMessageContaining("LightDevice");
    }
}
