package io.casehub.iot.homeassistant;

import io.casehub.iot.api.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

class HomeAssistantSupplementTest {

    private static final Instant NOW = Instant.parse("2026-06-09T10:00:00Z");

    // ── HomeAssistantLight ──────────────────────────────────────────────

    @Test
    void lightBuildsWithAllSupplementFields() {
        HomeAssistantLight light = HomeAssistantLight.builder()
                .deviceId("l1").deviceClass(DeviceClass.LIGHT).label("Kitchen Strip")
                .available(true).lastUpdated(NOW).tenancyId("t1")
                .on(true).brightness(200).colorTemp(4000)
                .rgbColor(new int[]{255, 128, 0})
                .effect("rainbow")
                .supportedColorModes(Set.of("rgb", "xy"))
                .build();

        assertThat(light).isInstanceOf(LightDevice.class);
        assertThat(light.rgbColor()).isPresent().get().isEqualTo(new int[]{255, 128, 0});
        assertThat(light.effect()).hasValue("rainbow");
        assertThat(light.supportedColorModes()).containsExactlyInAnyOrder("rgb", "xy");
    }

    @Test
    void lightBuildsWithAbsentSupplementFields() {
        HomeAssistantLight light = HomeAssistantLight.builder()
                .deviceId("l2").deviceClass(DeviceClass.LIGHT).label("Hallway Light")
                .available(true).lastUpdated(NOW).tenancyId("t1")
                .on(false)
                .build();

        assertThat(light.rgbColor()).isEmpty();
        assertThat(light.effect()).isEmpty();
        assertThat(light.supportedColorModes()).isEmpty();
    }

    @Test
    void lightCapabilitiesIncludeSupplementFields() {
        HomeAssistantLight light = HomeAssistantLight.builder()
                .deviceId("l3").deviceClass(DeviceClass.LIGHT).label("Living Room")
                .available(true).lastUpdated(NOW).tenancyId("t1")
                .on(true).brightness(100).colorTemp(3500)
                .rgbColor(new int[]{0, 0, 255})
                .effect("strobe")
                .supportedColorModes(Set.of("hs"))
                .build();

        Map<String, Object> caps = light.capabilities();

        // Inherited from DeviceEntity
        assertThat(caps).containsKey(DeviceEntity.CAP_AVAILABLE);
        // Inherited from LightDevice
        assertThat(caps).containsKeys(
                LightDevice.CAP_ON,
                LightDevice.CAP_BRIGHTNESS,
                LightDevice.CAP_COLOR_TEMP);
        // Supplement fields
        assertThat(caps).containsKeys(
                HomeAssistantLight.CAP_RGB_COLOR,
                HomeAssistantLight.CAP_EFFECT,
                HomeAssistantLight.CAP_SUPPORTED_COLOR_MODES);
    }

    // ── HomeAssistantThermostat ──────────────────────────────────────────

    @Test
    void thermostatBuildsWithSupplementFields() {
        Temperature current = new Temperature(new BigDecimal("21.5"), Temperature.TemperatureUnit.CELSIUS);
        Temperature target = new Temperature(new BigDecimal("22.0"), Temperature.TemperatureUnit.CELSIUS);

        HomeAssistantThermostat thermostat = HomeAssistantThermostat.builder()
                .deviceId("t1").deviceClass(DeviceClass.THERMOSTAT).label("Main HVAC")
                .available(true).lastUpdated(NOW).tenancyId("t1")
                .currentTemperature(current).targetTemperature(target).mode(ThermostatMode.HEAT)
                .presetMode("away")
                .swingMode("vertical")
                .hvacAction("heating")
                .build();

        assertThat(thermostat).isInstanceOf(ThermostatDevice.class);
        assertThat(thermostat.presetMode()).hasValue("away");
        assertThat(thermostat.swingMode()).hasValue("vertical");
        assertThat(thermostat.hvacAction()).hasValue("heating");
    }

    @Test
    void thermostatCapabilitiesIncludeSupplementFields() {
        Temperature current = new Temperature(new BigDecimal("20.0"), Temperature.TemperatureUnit.CELSIUS);
        Temperature target = new Temperature(new BigDecimal("23.0"), Temperature.TemperatureUnit.CELSIUS);

        HomeAssistantThermostat thermostat = HomeAssistantThermostat.builder()
                .deviceId("t2").deviceClass(DeviceClass.THERMOSTAT).label("Upstairs HVAC")
                .available(true).lastUpdated(NOW).tenancyId("t1")
                .currentTemperature(current).targetTemperature(target).mode(ThermostatMode.COOL)
                .presetMode("home")
                .swingMode("horizontal")
                .hvacAction("cooling")
                .build();

        Map<String, Object> caps = thermostat.capabilities();

        // Inherited
        assertThat(caps).containsKey(DeviceEntity.CAP_AVAILABLE);
        assertThat(caps).containsKeys(
                ThermostatDevice.CAP_CURRENT_TEMPERATURE,
                ThermostatDevice.CAP_TARGET_TEMPERATURE,
                ThermostatDevice.CAP_MODE);
        // Supplement
        assertThat(caps).containsKeys(
                HomeAssistantThermostat.CAP_PRESET_MODE,
                HomeAssistantThermostat.CAP_SWING_MODE,
                HomeAssistantThermostat.CAP_HVAC_ACTION);
    }

    // ── HomeAssistantLock ───────────────────────────────────────────────

    @Test
    void lockBuildsWithSupplementFields() {
        HomeAssistantLock lock = HomeAssistantLock.builder()
                .deviceId("k1").deviceClass(DeviceClass.LOCK).label("Front Door")
                .available(true).lastUpdated(NOW).tenancyId("t1")
                .locked(true)
                .changedBy("mobile_app")
                .codeSlot(3)
                .build();

        assertThat(lock).isInstanceOf(LockDevice.class);
        assertThat(lock.changedBy()).hasValue("mobile_app");
        assertThat(lock.codeSlot()).hasValue(3);
    }

    @Test
    void lockCapabilitiesIncludeSupplementFields() {
        HomeAssistantLock lock = HomeAssistantLock.builder()
                .deviceId("k2").deviceClass(DeviceClass.LOCK).label("Back Door")
                .available(true).lastUpdated(NOW).tenancyId("t1")
                .locked(false)
                .changedBy("keypad")
                .codeSlot(7)
                .build();

        Map<String, Object> caps = lock.capabilities();

        // Inherited
        assertThat(caps).containsKey(DeviceEntity.CAP_AVAILABLE);
        assertThat(caps).containsKey(LockDevice.CAP_LOCKED);
        // Supplement
        assertThat(caps).containsKeys(
                HomeAssistantLock.CAP_CHANGED_BY,
                HomeAssistantLock.CAP_CODE_SLOT);
    }

    // ── Cross-cutting: deriveChangedCapabilities detects supplement diffs ─

    @Test
    void supplementDeriveChangedCapabilitiesDetectsSupplementFieldChange() {
        HomeAssistantLight before = HomeAssistantLight.builder()
                .deviceId("l4").deviceClass(DeviceClass.LIGHT).label("Strip")
                .available(true).lastUpdated(NOW).tenancyId("t1")
                .on(true).brightness(200)
                .effect("rainbow")
                .build();

        HomeAssistantLight after = HomeAssistantLight.builder()
                .deviceId("l4").deviceClass(DeviceClass.LIGHT).label("Strip")
                .available(true).lastUpdated(NOW).tenancyId("t1")
                .on(true).brightness(200)
                .effect("strobe")
                .build();

        Set<String> changed = StateChangeEvent.deriveChangedCapabilities(before, after);
        assertThat(changed).containsExactly("effect");
    }
}
