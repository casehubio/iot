package io.casehub.iot.api;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class ExtensibleDeviceTest {

    private static final Instant TEST_INSTANT = Instant.parse("2026-06-07T10:00:00Z");
    private static final Instant NOW = TEST_INSTANT;

    @Test
    void lightDeviceBuildsWithAllFields() {
        LightDevice device = new LightDevice.Builder()
                .deviceId(UUID.randomUUID().toString())
                .label("Kitchen Light")
                .deviceClass(DeviceClass.LIGHT)
                .lastUpdated(TEST_INSTANT)
                .tenancyId("test-tenant")
                .on(true)
                .brightness(200)
                .colorTemp(370)
                .build();

        assertThat(device.isOn()).isTrue();
        assertThat(device.brightness()).hasValue(200);
        assertThat(device.colorTemp()).hasValue(370);
        assertThat(LightDevice.CAP_ON).isEqualTo("isOn");
        assertThat(LightDevice.CAP_BRIGHTNESS).isEqualTo("brightness");
        assertThat(LightDevice.CAP_COLOR_TEMP).isEqualTo("colorTemp");
    }

    @Test
    void lightDeviceBuildsWithOptionalFieldsAbsent() {
        LightDevice device = new LightDevice.Builder()
                .deviceId(UUID.randomUUID().toString())
                .label("Kitchen Light")
                .deviceClass(DeviceClass.LIGHT)
                .lastUpdated(TEST_INSTANT)
                .tenancyId("test-tenant")
                .on(false)
                .build();

        assertThat(device.isOn()).isFalse();
        assertThat(device.brightness()).isEmpty();
        assertThat(device.colorTemp()).isEmpty();
    }

    @Test
    void lightDeviceIsInstanceOfDeviceEntity() {
        LightDevice device = new LightDevice.Builder()
                .deviceId(UUID.randomUUID().toString())
                .label("Kitchen Light")
                .deviceClass(DeviceClass.LIGHT)
                .lastUpdated(TEST_INSTANT)
                .tenancyId("test-tenant")
                .on(true)
                .build();

        assertThat(device).isInstanceOf(DeviceEntity.class);
    }

    @Test
    void thermostatDeviceBuildsWithRequiredFields() {
        Temperature current = new Temperature(new BigDecimal("21.5"), Temperature.TemperatureUnit.CELSIUS);
        Temperature target = new Temperature(new BigDecimal("22.0"), Temperature.TemperatureUnit.CELSIUS);

        ThermostatDevice device = new ThermostatDevice.Builder()
                .deviceId(UUID.randomUUID().toString())
                .label("Main Thermostat")
                .deviceClass(DeviceClass.THERMOSTAT)
                .lastUpdated(TEST_INSTANT)
                .tenancyId("test-tenant")
                .currentTemperature(current)
                .targetTemperature(target)
                .mode(ThermostatMode.HEAT)
                .build();

        assertThat(device.currentTemperature()).isEqualTo(current);
        assertThat(device.targetTemperature()).isEqualTo(target);
        assertThat(device.mode()).isEqualTo(ThermostatMode.HEAT);
        assertThat(ThermostatDevice.CAP_CURRENT_TEMPERATURE).isEqualTo("currentTemperature");
        assertThat(ThermostatDevice.CAP_TARGET_TEMPERATURE).isEqualTo("targetTemperature");
        assertThat(ThermostatDevice.CAP_MODE).isEqualTo("mode");
    }

    @Test
    void thermostatDeviceRequiresAllFields() {
        Temperature temp = new Temperature(new BigDecimal("21.5"), Temperature.TemperatureUnit.CELSIUS);

        assertThatThrownBy(() ->
            new ThermostatDevice.Builder()
                    .deviceId(UUID.randomUUID().toString())
                    .label("Main Thermostat")
                    .deviceClass(DeviceClass.THERMOSTAT)
                    .lastUpdated(TEST_INSTANT)
                    .tenancyId("test-tenant")
                    .targetTemperature(temp)
                    .mode(ThermostatMode.HEAT)
                    .build()
        ).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() ->
            new ThermostatDevice.Builder()
                    .deviceId(UUID.randomUUID().toString())
                    .label("Main Thermostat")
                    .deviceClass(DeviceClass.THERMOSTAT)
                    .lastUpdated(TEST_INSTANT)
                    .tenancyId("test-tenant")
                    .currentTemperature(temp)
                    .mode(ThermostatMode.HEAT)
                    .build()
        ).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() ->
            new ThermostatDevice.Builder()
                    .deviceId(UUID.randomUUID().toString())
                    .label("Main Thermostat")
                    .deviceClass(DeviceClass.THERMOSTAT)
                    .lastUpdated(TEST_INSTANT)
                    .tenancyId("test-tenant")
                    .currentTemperature(temp)
                    .targetTemperature(temp)
                    .build()
        ).isInstanceOf(NullPointerException.class);
    }

    @Test
    void lockDeviceBuildsWithRequiredFields() {
        LockDevice device = new LockDevice.Builder()
                .deviceId(UUID.randomUUID().toString())
                .label("Front Door Lock")
                .deviceClass(DeviceClass.LOCK)
                .lastUpdated(TEST_INSTANT)
                .tenancyId("test-tenant")
                .locked(true)
                .build();

        assertThat(device.isLocked()).isTrue();
        assertThat(LockDevice.CAP_LOCKED).isEqualTo("isLocked");
    }

    @Test
    void lockDeviceIsInstanceOfDeviceEntity() {
        LockDevice device = new LockDevice.Builder()
                .deviceId(UUID.randomUUID().toString())
                .label("Front Door Lock")
                .deviceClass(DeviceClass.LOCK)
                .lastUpdated(TEST_INSTANT)
                .tenancyId("test-tenant")
                .locked(false)
                .build();

        assertThat(device).isInstanceOf(DeviceEntity.class);
    }

    @Test
    void coverDeviceBuildsWithPosition() {
        CoverDevice device = new CoverDevice.Builder()
                .deviceId(UUID.randomUUID().toString())
                .label("Living Room Blind")
                .deviceClass(DeviceClass.COVER)
                .lastUpdated(TEST_INSTANT)
                .tenancyId("test-tenant")
                .position(75)
                .moving(false)
                .build();

        assertThat(device.position()).hasValue(75);
        assertThat(device.isMoving()).isFalse();
        assertThat(CoverDevice.CAP_POSITION).isEqualTo("position");
        assertThat(CoverDevice.CAP_MOVING).isEqualTo("isMoving");
    }

    @Test
    void coverDevicePositionAbsent() {
        CoverDevice device = new CoverDevice.Builder()
                .deviceId(UUID.randomUUID().toString())
                .label("Garage Door")
                .deviceClass(DeviceClass.COVER)
                .lastUpdated(TEST_INSTANT)
                .tenancyId("test-tenant")
                .moving(false)
                .build();

        assertThat(device.position()).isEmpty();
        assertThat(device.isMoving()).isFalse();
    }

    @Test
    void coverDeviceIsInstanceOfDeviceEntity() {
        CoverDevice device = new CoverDevice.Builder()
                .deviceId(UUID.randomUUID().toString())
                .label("Living Room Blind")
                .deviceClass(DeviceClass.COVER)
                .lastUpdated(TEST_INSTANT)
                .tenancyId("test-tenant")
                .position(50)
                .moving(true)
                .build();

        assertThat(device).isInstanceOf(DeviceEntity.class);
    }

    // Simulates a vendor supplement — e.g. HomeAssistantLight extends LightDevice.
    // Declared at class level (not method-local) to avoid a JDK compiler bug (JDK-8319461)
    // where javac's Lower phase recurses infinitely on local classes with self-referential generics.
    static class TestSupplementLight extends LightDevice {
        private final String extraField;

        TestSupplementLight(Builder builder) {
            super(builder);
            this.extraField = builder.extraField;
        }

        String extraField() { return extraField; }

        static class Builder extends LightDevice.AbstractBuilder<TestSupplementLight, Builder> {
            String extraField;

            Builder extraField(String v) { this.extraField = v; return self(); }

            @Override protected Builder self() { return this; }
            @Override public TestSupplementLight build() { return new TestSupplementLight(this); }
        }
    }

    @Test
    void supplementBuilderChainWorksAcrossInheritance() {
        var device = new TestSupplementLight.Builder()
            .deviceId("l1").deviceClass(DeviceClass.LIGHT).label("Test Light")
            .available(true).lastUpdated(NOW).tenancyId("t1")
            .on(true).brightness(200).colorTemp(4000)
            .extraField("vendor-specific")
            .build();

        assertThat(device).isInstanceOf(LightDevice.class);
        assertThat(device).isInstanceOf(TestSupplementLight.class);
        assertThat(device.isOn()).isTrue();
        assertThat(device.brightness()).hasValue(200);
        assertThat(device.extraField()).isEqualTo("vendor-specific");
        assertThat(device.deviceId()).isEqualTo("l1");
    }
}
