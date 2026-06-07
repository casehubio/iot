package io.casehub.iot.api;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class ExtensibleDeviceTest {

    private static final Instant TEST_INSTANT = Instant.parse("2026-06-07T10:00:00Z");
    private static final Instant NOW = TEST_INSTANT;

    @Test
    void lightDeviceBuildsWithAllFields() {
        LightDevice device = LightDevice.builder()
                .deviceId(UUID.randomUUID().toString())
                .label("Kitchen Light")
                .deviceClass(DeviceClass.LIGHT)
                .lastUpdated(TEST_INSTANT)
                .tenancyId("test-tenant")
                .on(true)
                .brightness(200)
                .colorTemp(370)
                .build();

        assertTrue(device.isOn());
        assertTrue(device.brightness().isPresent());
        assertEquals(200, device.brightness().get());
        assertTrue(device.colorTemp().isPresent());
        assertEquals(370, device.colorTemp().get());
        assertEquals("isOn", LightDevice.CAP_ON);
        assertEquals("brightness", LightDevice.CAP_BRIGHTNESS);
        assertEquals("colorTemp", LightDevice.CAP_COLOR_TEMP);
    }

    @Test
    void lightDeviceBuildsWithOptionalFieldsAbsent() {
        LightDevice device = LightDevice.builder()
                .deviceId(UUID.randomUUID().toString())
                .label("Kitchen Light")
                .deviceClass(DeviceClass.LIGHT)
                .lastUpdated(TEST_INSTANT)
                .tenancyId("test-tenant")
                .on(false)
                .build();

        assertFalse(device.isOn());
        assertTrue(device.brightness().isEmpty());
        assertTrue(device.colorTemp().isEmpty());
    }

    @Test
    void lightDeviceIsInstanceOfDeviceEntity() {
        LightDevice device = LightDevice.builder()
                .deviceId(UUID.randomUUID().toString())
                .label("Kitchen Light")
                .deviceClass(DeviceClass.LIGHT)
                .lastUpdated(TEST_INSTANT)
                .tenancyId("test-tenant")
                .on(true)
                .build();

        assertInstanceOf(DeviceEntity.class, device);
    }

    @Test
    void thermostatDeviceBuildsWithRequiredFields() {
        Temperature current = new Temperature(new BigDecimal("21.5"), Temperature.TemperatureUnit.CELSIUS);
        Temperature target = new Temperature(new BigDecimal("22.0"), Temperature.TemperatureUnit.CELSIUS);

        ThermostatDevice device = ThermostatDevice.builder()
                .deviceId(UUID.randomUUID().toString())
                .label("Main Thermostat")
                .deviceClass(DeviceClass.THERMOSTAT)
                .lastUpdated(TEST_INSTANT)
                .tenancyId("test-tenant")
                .currentTemperature(current)
                .targetTemperature(target)
                .mode(ThermostatMode.HEAT)
                .build();

        assertEquals(current, device.currentTemperature());
        assertEquals(target, device.targetTemperature());
        assertEquals(ThermostatMode.HEAT, device.mode());
        assertEquals("currentTemperature", ThermostatDevice.CAP_CURRENT_TEMPERATURE);
        assertEquals("targetTemperature", ThermostatDevice.CAP_TARGET_TEMPERATURE);
        assertEquals("mode", ThermostatDevice.CAP_MODE);
    }

    @Test
    void thermostatDeviceRequiresAllFields() {
        Temperature temp = new Temperature(new BigDecimal("21.5"), Temperature.TemperatureUnit.CELSIUS);

        assertThrows(NullPointerException.class, () -> {
            ThermostatDevice.builder()
                    .deviceId(UUID.randomUUID().toString())
                    .label("Main Thermostat")
                    .deviceClass(DeviceClass.THERMOSTAT)
                    .lastUpdated(TEST_INSTANT)
                    .tenancyId("test-tenant")
                    .targetTemperature(temp)
                    .mode(ThermostatMode.HEAT)
                    .build();
        });

        assertThrows(NullPointerException.class, () -> {
            ThermostatDevice.builder()
                    .deviceId(UUID.randomUUID().toString())
                    .label("Main Thermostat")
                    .deviceClass(DeviceClass.THERMOSTAT)
                    .lastUpdated(TEST_INSTANT)
                    .tenancyId("test-tenant")
                    .currentTemperature(temp)
                    .mode(ThermostatMode.HEAT)
                    .build();
        });

        assertThrows(NullPointerException.class, () -> {
            ThermostatDevice.builder()
                    .deviceId(UUID.randomUUID().toString())
                    .label("Main Thermostat")
                    .deviceClass(DeviceClass.THERMOSTAT)
                    .lastUpdated(TEST_INSTANT)
                    .tenancyId("test-tenant")
                    .currentTemperature(temp)
                    .targetTemperature(temp)
                    .build();
        });
    }

    @Test
    void lockDeviceBuildsWithRequiredFields() {
        LockDevice device = LockDevice.builder()
                .deviceId(UUID.randomUUID().toString())
                .label("Front Door Lock")
                .deviceClass(DeviceClass.LOCK)
                .lastUpdated(TEST_INSTANT)
                .tenancyId("test-tenant")
                .locked(true)
                .build();

        assertTrue(device.isLocked());
        assertEquals("isLocked", LockDevice.CAP_LOCKED);
    }

    @Test
    void lockDeviceIsInstanceOfDeviceEntity() {
        LockDevice device = LockDevice.builder()
                .deviceId(UUID.randomUUID().toString())
                .label("Front Door Lock")
                .deviceClass(DeviceClass.LOCK)
                .lastUpdated(TEST_INSTANT)
                .tenancyId("test-tenant")
                .locked(false)
                .build();

        assertInstanceOf(DeviceEntity.class, device);
    }

    @Test
    void coverDeviceBuildsWithRequiredFields() {
        CoverDevice device = CoverDevice.builder()
                .deviceId(UUID.randomUUID().toString())
                .label("Living Room Blind")
                .deviceClass(DeviceClass.COVER)
                .lastUpdated(TEST_INSTANT)
                .tenancyId("test-tenant")
                .position(75)
                .moving(false)
                .build();

        assertEquals(75, device.position());
        assertFalse(device.isMoving());
        assertEquals("position", CoverDevice.CAP_POSITION);
        assertEquals("isMoving", CoverDevice.CAP_MOVING);
    }

    @Test
    void coverDeviceIsInstanceOfDeviceEntity() {
        CoverDevice device = CoverDevice.builder()
                .deviceId(UUID.randomUUID().toString())
                .label("Living Room Blind")
                .deviceClass(DeviceClass.COVER)
                .lastUpdated(TEST_INSTANT)
                .tenancyId("test-tenant")
                .position(50)
                .moving(true)
                .build();

        assertInstanceOf(DeviceEntity.class, device);
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
