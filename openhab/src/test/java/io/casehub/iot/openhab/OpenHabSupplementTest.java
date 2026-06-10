package io.casehub.iot.openhab;

import io.casehub.iot.api.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

class OpenHabSupplementTest {

    private static final Instant NOW = Instant.parse("2026-06-10T10:00:00Z");

    // ── OpenHabLight ──────────────────────────────────────────────

    @Test
    void lightBuildsWithAllSupplementFields() {
        OpenHabHsbType hsb = new OpenHabHsbType(
                new BigDecimal("180.0"),
                new BigDecimal("75.0"),
                new BigDecimal("90.0")
        );

        OpenHabLight light = OpenHabLight.builder()
                .deviceId("l1").deviceClass(DeviceClass.LIGHT).label("Kitchen Strip")
                .available(true).lastUpdated(NOW).tenancyId("t1")
                .on(true).brightness(200).colorTemp(4000)
                .hsb(hsb)
                .build();

        assertThat(light).isInstanceOf(LightDevice.class);
        assertThat(light.hsb()).isPresent().hasValue(hsb);
    }

    @Test
    void lightBuildsWithAbsentSupplementFields() {
        OpenHabLight light = OpenHabLight.builder()
                .deviceId("l2").deviceClass(DeviceClass.LIGHT).label("Hallway Light")
                .available(true).lastUpdated(NOW).tenancyId("t1")
                .on(false)
                .build();

        assertThat(light.hsb()).isEmpty();
    }

    @Test
    void lightCapabilitiesIncludeSupplementFields() {
        OpenHabHsbType hsb = new OpenHabHsbType(
                new BigDecimal("240.0"),
                new BigDecimal("100.0"),
                new BigDecimal("50.0")
        );

        OpenHabLight light = OpenHabLight.builder()
                .deviceId("l3").deviceClass(DeviceClass.LIGHT).label("Living Room")
                .available(true).lastUpdated(NOW).tenancyId("t1")
                .on(true).brightness(100).colorTemp(3500)
                .hsb(hsb)
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
        assertThat(caps).containsKey(OpenHabLight.CAP_HSB);
    }

    @Test
    void lightDeriveChangedCapabilitiesDetectsSupplementFieldChange() {
        OpenHabHsbType before = new OpenHabHsbType(
                new BigDecimal("120.0"),
                new BigDecimal("50.0"),
                new BigDecimal("75.0")
        );
        OpenHabHsbType after = new OpenHabHsbType(
                new BigDecimal("180.0"),
                new BigDecimal("60.0"),
                new BigDecimal("80.0")
        );

        OpenHabLight beforeLight = OpenHabLight.builder()
                .deviceId("l4").deviceClass(DeviceClass.LIGHT).label("Strip")
                .available(true).lastUpdated(NOW).tenancyId("t1")
                .on(true).brightness(200)
                .hsb(before)
                .build();

        OpenHabLight afterLight = OpenHabLight.builder()
                .deviceId("l4").deviceClass(DeviceClass.LIGHT).label("Strip")
                .available(true).lastUpdated(NOW).tenancyId("t1")
                .on(true).brightness(200)
                .hsb(after)
                .build();

        Set<String> changed = StateChangeEvent.deriveChangedCapabilities(beforeLight, afterLight);
        assertThat(changed).containsExactly("hsb");
    }

    @Test
    void lightToBuilderRoundTripPreservesAllFields() {
        OpenHabHsbType hsb = new OpenHabHsbType(
                new BigDecimal("90.0"),
                new BigDecimal("100.0"),
                new BigDecimal("100.0")
        );

        OpenHabLight original = OpenHabLight.builder()
                .deviceId("l5").deviceClass(DeviceClass.LIGHT).label("Study")
                .available(true).lastUpdated(NOW).tenancyId("t1")
                .on(true).brightness(150).colorTemp(5000)
                .hsb(hsb)
                .build();

        OpenHabLight copy = original.toBuilder().build();

        assertThat(copy.deviceId()).isEqualTo(original.deviceId());
        assertThat(copy.label()).isEqualTo(original.label());
        assertThat(copy.isOn()).isEqualTo(original.isOn());
        assertThat(copy.brightness()).isEqualTo(original.brightness());
        assertThat(copy.colorTemp()).isEqualTo(original.colorTemp());
        assertThat(copy.hsb()).isEqualTo(original.hsb());
    }

    // ── OpenHabThermostat ──────────────────────────────────────────

    @Test
    void thermostatBuildsWithSupplementFields() {
        Temperature current = new Temperature(new BigDecimal("21.5"), Temperature.TemperatureUnit.CELSIUS);
        Temperature target = new Temperature(new BigDecimal("22.0"), Temperature.TemperatureUnit.CELSIUS);

        OpenHabThermostat thermostat = OpenHabThermostat.builder()
                .deviceId("t1").deviceClass(DeviceClass.THERMOSTAT).label("Main HVAC")
                .available(true).lastUpdated(NOW).tenancyId("t1")
                .currentTemperature(current).targetTemperature(target).mode(ThermostatMode.HEAT)
                .heatingDemand(new BigDecimal("85.0"))
                .coolingDemand(new BigDecimal("0.0"))
                .build();

        assertThat(thermostat).isInstanceOf(ThermostatDevice.class);
        assertThat(thermostat.heatingDemand()).hasValue(new BigDecimal("85.0"));
        assertThat(thermostat.coolingDemand()).hasValue(new BigDecimal("0.0"));
    }

    @Test
    void thermostatBuildsWithAbsentSupplementFields() {
        Temperature current = new Temperature(new BigDecimal("20.0"), Temperature.TemperatureUnit.CELSIUS);
        Temperature target = new Temperature(new BigDecimal("23.0"), Temperature.TemperatureUnit.CELSIUS);

        OpenHabThermostat thermostat = OpenHabThermostat.builder()
                .deviceId("t2").deviceClass(DeviceClass.THERMOSTAT).label("Upstairs HVAC")
                .available(true).lastUpdated(NOW).tenancyId("t1")
                .currentTemperature(current).targetTemperature(target).mode(ThermostatMode.COOL)
                .build();

        assertThat(thermostat.heatingDemand()).isEmpty();
        assertThat(thermostat.coolingDemand()).isEmpty();
    }

    @Test
    void thermostatCapabilitiesIncludeSupplementFields() {
        Temperature current = new Temperature(new BigDecimal("20.0"), Temperature.TemperatureUnit.CELSIUS);
        Temperature target = new Temperature(new BigDecimal("23.0"), Temperature.TemperatureUnit.CELSIUS);

        OpenHabThermostat thermostat = OpenHabThermostat.builder()
                .deviceId("t3").deviceClass(DeviceClass.THERMOSTAT).label("Basement HVAC")
                .available(true).lastUpdated(NOW).tenancyId("t1")
                .currentTemperature(current).targetTemperature(target).mode(ThermostatMode.COOL)
                .heatingDemand(new BigDecimal("10.0"))
                .coolingDemand(new BigDecimal("75.0"))
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
                OpenHabThermostat.CAP_HEATING_DEMAND,
                OpenHabThermostat.CAP_COOLING_DEMAND);
    }

    @Test
    void thermostatDeriveChangedCapabilitiesDetectsSupplementFieldChange() {
        Temperature current = new Temperature(new BigDecimal("21.0"), Temperature.TemperatureUnit.CELSIUS);
        Temperature target = new Temperature(new BigDecimal("22.0"), Temperature.TemperatureUnit.CELSIUS);

        OpenHabThermostat before = OpenHabThermostat.builder()
                .deviceId("t4").deviceClass(DeviceClass.THERMOSTAT).label("Main")
                .available(true).lastUpdated(NOW).tenancyId("t1")
                .currentTemperature(current).targetTemperature(target).mode(ThermostatMode.HEAT)
                .heatingDemand(new BigDecimal("50.0"))
                .build();

        OpenHabThermostat after = OpenHabThermostat.builder()
                .deviceId("t4").deviceClass(DeviceClass.THERMOSTAT).label("Main")
                .available(true).lastUpdated(NOW).tenancyId("t1")
                .currentTemperature(current).targetTemperature(target).mode(ThermostatMode.HEAT)
                .heatingDemand(new BigDecimal("90.0"))
                .build();

        Set<String> changed = StateChangeEvent.deriveChangedCapabilities(before, after);
        assertThat(changed).containsExactly("heatingDemand");
    }

    @Test
    void thermostatToBuilderRoundTripPreservesAllFields() {
        Temperature current = new Temperature(new BigDecimal("19.0"), Temperature.TemperatureUnit.CELSIUS);
        Temperature target = new Temperature(new BigDecimal("21.0"), Temperature.TemperatureUnit.CELSIUS);

        OpenHabThermostat original = OpenHabThermostat.builder()
                .deviceId("t5").deviceClass(DeviceClass.THERMOSTAT).label("Office")
                .available(true).lastUpdated(NOW).tenancyId("t1")
                .currentTemperature(current).targetTemperature(target).mode(ThermostatMode.AUTO)
                .heatingDemand(new BigDecimal("30.0"))
                .coolingDemand(new BigDecimal("0.0"))
                .build();

        OpenHabThermostat copy = original.toBuilder().build();

        assertThat(copy.deviceId()).isEqualTo(original.deviceId());
        assertThat(copy.currentTemperature()).isEqualTo(original.currentTemperature());
        assertThat(copy.targetTemperature()).isEqualTo(original.targetTemperature());
        assertThat(copy.mode()).isEqualTo(original.mode());
        assertThat(copy.heatingDemand()).isEqualTo(original.heatingDemand());
        assertThat(copy.coolingDemand()).isEqualTo(original.coolingDemand());
    }

    // ── OpenHabRollershutter ───────────────────────────────────────

    @Test
    void rollershutterBuildsWithSupplementFields() {
        OpenHabRollershutter cover = OpenHabRollershutter.builder()
                .deviceId("c1").deviceClass(DeviceClass.COVER).label("Bedroom Blinds")
                .available(true).lastUpdated(NOW).tenancyId("t1")
                .position(50).moving(false)
                .upDown(OpenHabUpDownType.STOP)
                .build();

        assertThat(cover).isInstanceOf(CoverDevice.class);
        assertThat(cover.upDown()).hasValue(OpenHabUpDownType.STOP);
    }

    @Test
    void rollershutterBuildsWithAbsentSupplementFields() {
        OpenHabRollershutter cover = OpenHabRollershutter.builder()
                .deviceId("c2").deviceClass(DeviceClass.COVER).label("Living Room Curtains")
                .available(true).lastUpdated(NOW).tenancyId("t1")
                .position(100).moving(false)
                .build();

        assertThat(cover.upDown()).isEmpty();
    }

    @Test
    void rollershutterCapabilitiesIncludeSupplementFields() {
        OpenHabRollershutter cover = OpenHabRollershutter.builder()
                .deviceId("c3").deviceClass(DeviceClass.COVER).label("Office Shades")
                .available(true).lastUpdated(NOW).tenancyId("t1")
                .position(25).moving(true)
                .upDown(OpenHabUpDownType.UP)
                .build();

        Map<String, Object> caps = cover.capabilities();

        // Inherited
        assertThat(caps).containsKey(DeviceEntity.CAP_AVAILABLE);
        assertThat(caps).containsKeys(
                CoverDevice.CAP_POSITION,
                CoverDevice.CAP_MOVING);
        // Supplement
        assertThat(caps).containsKey(OpenHabRollershutter.CAP_UP_DOWN);
    }

    @Test
    void rollershutterDeriveChangedCapabilitiesDetectsSupplementFieldChange() {
        OpenHabRollershutter before = OpenHabRollershutter.builder()
                .deviceId("c4").deviceClass(DeviceClass.COVER).label("Garage Door")
                .available(true).lastUpdated(NOW).tenancyId("t1")
                .position(0).moving(false)
                .upDown(OpenHabUpDownType.STOP)
                .build();

        OpenHabRollershutter after = OpenHabRollershutter.builder()
                .deviceId("c4").deviceClass(DeviceClass.COVER).label("Garage Door")
                .available(true).lastUpdated(NOW).tenancyId("t1")
                .position(0).moving(true)
                .upDown(OpenHabUpDownType.UP)
                .build();

        Set<String> changed = StateChangeEvent.deriveChangedCapabilities(before, after);
        assertThat(changed).containsExactlyInAnyOrder("isMoving", "upDown");
    }

    @Test
    void rollershutterToBuilderRoundTripPreservesAllFields() {
        OpenHabRollershutter original = OpenHabRollershutter.builder()
                .deviceId("c5").deviceClass(DeviceClass.COVER).label("Kitchen Blinds")
                .available(true).lastUpdated(NOW).tenancyId("t1")
                .position(75).moving(false)
                .upDown(OpenHabUpDownType.DOWN)
                .build();

        OpenHabRollershutter copy = original.toBuilder().build();

        assertThat(copy.deviceId()).isEqualTo(original.deviceId());
        assertThat(copy.position()).isEqualTo(original.position());
        assertThat(copy.isMoving()).isEqualTo(original.isMoving());
        assertThat(copy.upDown()).isEqualTo(original.upDown());
    }

    @Test
    void rollershutterUpDownReturnsEmptyWhenNotSet() {
        OpenHabRollershutter cover = OpenHabRollershutter.builder()
                .deviceId("c6").deviceClass(DeviceClass.COVER).label("Patio Door")
                .available(true).lastUpdated(NOW).tenancyId("t1")
                .position(33).moving(false)
                .build();

        assertThat(cover.upDown()).isEmpty();
    }
}
