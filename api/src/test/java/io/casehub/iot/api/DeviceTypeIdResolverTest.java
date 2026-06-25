package io.casehub.iot.api;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeviceTypeIdResolverTest {

    private static final Instant NOW = Instant.parse("2026-06-07T10:00:00Z");

    private static JsonMapper mapper;

    @BeforeAll
    static void setupMapper() {
        mapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .build();
    }

    // --- Round-trip tests for all 10 common device types ---

    @Test
    void roundTripSwitchDevice() throws Exception {
        SwitchDevice device = SwitchDevice.builder()
                .deviceId("sw-1").deviceClass(DeviceClass.SWITCH).label("Kitchen Switch")
                .available(true).lastUpdated(NOW).tenancyId("t1").providerId("test")
                .on(true)
                .build();

        String json = mapper.writeValueAsString(device);
        assertThat(json).contains("\"@deviceType\":\"SWITCH:SwitchDevice\"");

        DeviceEntity deserialized = mapper.readValue(json, DeviceEntity.class);
        assertThat(deserialized).isInstanceOf(SwitchDevice.class);
        SwitchDevice result = (SwitchDevice) deserialized;
        assertThat(result.deviceId()).isEqualTo("sw-1");
        assertThat(result.deviceClass()).isEqualTo(DeviceClass.SWITCH);
        assertThat(result.label()).isEqualTo("Kitchen Switch");
        assertThat(result.available()).isTrue();
        assertThat(result.lastUpdated()).isEqualTo(NOW);
        assertThat(result.tenancyId()).isEqualTo("t1");
        assertThat(result.isOn()).isTrue();
    }

    @Test
    void roundTripLightDevice() throws Exception {
        LightDevice device = new LightDevice.Builder()
                .deviceId("lt-1").deviceClass(DeviceClass.LIGHT).label("Living Room Light")
                .available(true).lastUpdated(NOW).tenancyId("t1").providerId("test")
                .on(true).brightness(200).colorTemp(4000)
                .build();

        String json = mapper.writeValueAsString(device);
        assertThat(json).contains("\"@deviceType\":\"LIGHT:LightDevice\"");

        DeviceEntity deserialized = mapper.readValue(json, DeviceEntity.class);
        assertThat(deserialized).isInstanceOf(LightDevice.class);
        LightDevice result = (LightDevice) deserialized;
        assertThat(result.deviceId()).isEqualTo("lt-1");
        assertThat(result.isOn()).isTrue();
        assertThat(result.brightness()).hasValue(200);
        assertThat(result.colorTemp()).hasValue(4000);
    }

    @Test
    void roundTripThermostatDevice() throws Exception {
        Temperature current = new Temperature(new BigDecimal("21.5"), Temperature.TemperatureUnit.CELSIUS);
        Temperature target = new Temperature(new BigDecimal("23.0"), Temperature.TemperatureUnit.CELSIUS);

        ThermostatDevice device = new ThermostatDevice.Builder()
                .deviceId("th-1").deviceClass(DeviceClass.THERMOSTAT).label("Main Thermostat")
                .available(true).lastUpdated(NOW).tenancyId("t1").providerId("test")
                .currentTemperature(current).targetTemperature(target).mode(ThermostatMode.HEAT)
                .build();

        String json = mapper.writeValueAsString(device);
        assertThat(json).contains("\"@deviceType\":\"THERMOSTAT:ThermostatDevice\"");

        DeviceEntity deserialized = mapper.readValue(json, DeviceEntity.class);
        assertThat(deserialized).isInstanceOf(ThermostatDevice.class);
        ThermostatDevice result = (ThermostatDevice) deserialized;
        assertThat(result.currentTemperature()).isEqualTo(current);
        assertThat(result.targetTemperature()).isEqualTo(target);
        assertThat(result.mode()).isEqualTo(ThermostatMode.HEAT);
    }

    @Test
    void roundTripSensorDevice() throws Exception {
        SensorDevice device = SensorDevice.builder()
                .deviceId("sn-1").deviceClass(DeviceClass.SENSOR).label("Outdoor Temp")
                .available(true).lastUpdated(NOW).tenancyId("t1").providerId("test")
                .sensorType(SensorType.TEMPERATURE)
                .numericValue(new BigDecimal("18.3"))
                .unit("C")
                .build();

        String json = mapper.writeValueAsString(device);
        assertThat(json).contains("\"@deviceType\":\"SENSOR:SensorDevice\"");

        DeviceEntity deserialized = mapper.readValue(json, DeviceEntity.class);
        assertThat(deserialized).isInstanceOf(SensorDevice.class);
        SensorDevice result = (SensorDevice) deserialized;
        assertThat(result.sensorType()).isEqualTo(SensorType.TEMPERATURE);
        assertThat(result.numericValue()).hasValue(new BigDecimal("18.3"));
        assertThat(result.unit()).hasValue("C");
        assertThat(result.binaryValue()).isEmpty();
    }

    @Test
    void roundTripPresenceSensor() throws Exception {
        PresenceSensor device = PresenceSensor.builder()
                .deviceId("ps-1").deviceClass(DeviceClass.PRESENCE_SENSOR).label("Hallway Motion")
                .available(true).lastUpdated(NOW).tenancyId("t1").providerId("test")
                .present(true).lastSeen(NOW)
                .build();

        String json = mapper.writeValueAsString(device);
        assertThat(json).contains("\"@deviceType\":\"PRESENCE_SENSOR:PresenceSensor\"");

        DeviceEntity deserialized = mapper.readValue(json, DeviceEntity.class);
        assertThat(deserialized).isInstanceOf(PresenceSensor.class);
        PresenceSensor result = (PresenceSensor) deserialized;
        assertThat(result.isPresent()).isTrue();
        assertThat(result.lastSeen()).isEqualTo(NOW);
    }

    @Test
    void roundTripPowerSensor() throws Exception {
        PowerSensor device = PowerSensor.builder()
                .deviceId("pw-1").deviceClass(DeviceClass.POWER_SENSOR).label("Main Circuit")
                .available(true).lastUpdated(NOW).tenancyId("t1").providerId("test")
                .power(new BigDecimal("1500.50")).energy(new BigDecimal("3456.78"))
                .build();

        String json = mapper.writeValueAsString(device);
        assertThat(json).contains("\"@deviceType\":\"POWER_SENSOR:PowerSensor\"");

        DeviceEntity deserialized = mapper.readValue(json, DeviceEntity.class);
        assertThat(deserialized).isInstanceOf(PowerSensor.class);
        PowerSensor result = (PowerSensor) deserialized;
        assertThat(result.power()).hasValue(new BigDecimal("1500.50"));
        assertThat(result.energy()).hasValue(new BigDecimal("3456.78"));
    }

    @Test
    void roundTripLockDevice() throws Exception {
        LockDevice device = new LockDevice.Builder()
                .deviceId("lk-1").deviceClass(DeviceClass.LOCK).label("Front Door")
                .available(true).lastUpdated(NOW).tenancyId("t1").providerId("test")
                .locked(true)
                .build();

        String json = mapper.writeValueAsString(device);
        assertThat(json).contains("\"@deviceType\":\"LOCK:LockDevice\"");

        DeviceEntity deserialized = mapper.readValue(json, DeviceEntity.class);
        assertThat(deserialized).isInstanceOf(LockDevice.class);
        LockDevice result = (LockDevice) deserialized;
        assertThat(result.isLocked()).isTrue();
    }

    @Test
    void roundTripCoverDevice() throws Exception {
        CoverDevice device = new CoverDevice.Builder()
                .deviceId("cv-1").deviceClass(DeviceClass.COVER).label("Living Room Blind")
                .available(true).lastUpdated(NOW).tenancyId("t1").providerId("test")
                .position(75).moving(false)
                .build();

        String json = mapper.writeValueAsString(device);
        assertThat(json).contains("\"@deviceType\":\"COVER:CoverDevice\"");

        DeviceEntity deserialized = mapper.readValue(json, DeviceEntity.class);
        assertThat(deserialized).isInstanceOf(CoverDevice.class);
        CoverDevice result = (CoverDevice) deserialized;
        assertThat(result.position()).hasValue(75);
        assertThat(result.isMoving()).isFalse();
    }

    @Test
    void roundTripMediaPlayerDevice() throws Exception {
        MediaPlayerDevice device = MediaPlayerDevice.builder()
                .deviceId("mp-1").deviceClass(DeviceClass.MEDIA_PLAYER).label("Living Room TV")
                .available(true).lastUpdated(NOW).tenancyId("t1").providerId("test")
                .playing(true).volume(75)
                .build();

        String json = mapper.writeValueAsString(device);
        assertThat(json).contains("\"@deviceType\":\"MEDIA_PLAYER:MediaPlayerDevice\"");

        DeviceEntity deserialized = mapper.readValue(json, DeviceEntity.class);
        assertThat(deserialized).isInstanceOf(MediaPlayerDevice.class);
        MediaPlayerDevice result = (MediaPlayerDevice) deserialized;
        assertThat(result.isPlaying()).isTrue();
        assertThat(result.volume()).hasValue(75);
    }

    @Test
    void roundTripFanDevice() throws Exception {
        FanDevice device = FanDevice.builder()
                .deviceId("fn-1").deviceClass(DeviceClass.FAN).label("Bedroom Fan")
                .available(true).lastUpdated(NOW).tenancyId("t1").providerId("test")
                .on(true).speed(3)
                .build();

        String json = mapper.writeValueAsString(device);
        assertThat(json).contains("\"@deviceType\":\"FAN:FanDevice\"");

        DeviceEntity deserialized = mapper.readValue(json, DeviceEntity.class);
        assertThat(deserialized).isInstanceOf(FanDevice.class);
        FanDevice result = (FanDevice) deserialized;
        assertThat(result.isOn()).isTrue();
        assertThat(result.speed()).hasValue(3);
    }

    @Test
    void roundTripCameraDevice() throws Exception {
        CameraDevice device = CameraDevice.builder()
                .deviceId("cam-1").deviceClass(DeviceClass.CAMERA).label("Front Door Camera")
                .available(true).lastUpdated(NOW).tenancyId("t1").providerId("test")
                .streaming(true)
                .build();

        String json = mapper.writeValueAsString(device);
        assertThat(json).contains("\"@deviceType\":\"CAMERA:CameraDevice\"");

        DeviceEntity deserialized = mapper.readValue(json, DeviceEntity.class);
        assertThat(deserialized).isInstanceOf(CameraDevice.class);
        CameraDevice result = (CameraDevice) deserialized;
        assertThat(result.isStreaming()).isTrue();
    }

    // --- Graceful degradation: unknown compound ID falls back to common type ---

    @Test
    void unknownCompoundIdFallsBackToCommonType() throws Exception {
        // Simulates receiving JSON from a remote agent that has a vendor supplement type
        // the server doesn't know about — e.g. HomeAssistantThermostat
        String json = """
                {
                    "@deviceType": "THERMOSTAT:HomeAssistantThermostat",
                    "deviceId": "th-2",
                    "deviceClass": "THERMOSTAT",
                    "label": "HA Thermostat",
                    "available": true,
                    "lastUpdated": "2026-06-07T10:00:00Z",
                    "tenancyId": "t1",
                    "providerId": "test",
                    "currentTemperature": {"value": 20.0, "unit": "CELSIUS"},
                    "targetTemperature": {"value": 22.0, "unit": "CELSIUS"},
                    "mode": "AUTO",
                    "hvacAction": "heating",
                    "unknownVendorField": "should-be-ignored"
                }
                """;

        DeviceEntity deserialized = mapper.readValue(json, DeviceEntity.class);
        assertThat(deserialized).isInstanceOf(ThermostatDevice.class);
        ThermostatDevice result = (ThermostatDevice) deserialized;
        assertThat(result.deviceId()).isEqualTo("th-2");
        assertThat(result.label()).isEqualTo("HA Thermostat");
        assertThat(result.mode()).isEqualTo(ThermostatMode.AUTO);
    }

    @Test
    void unknownCompoundIdFallsBackForLight() throws Exception {
        String json = """
                {
                    "@deviceType": "LIGHT:OpenHabLight",
                    "deviceId": "lt-2",
                    "deviceClass": "LIGHT",
                    "label": "OH Light",
                    "available": true,
                    "lastUpdated": "2026-06-07T10:00:00Z",
                    "tenancyId": "t1",
                    "providerId": "test",
                    "on": true,
                    "brightness": 150,
                    "colorTemp": 3000,
                    "unknownField": 42
                }
                """;

        DeviceEntity deserialized = mapper.readValue(json, DeviceEntity.class);
        assertThat(deserialized).isInstanceOf(LightDevice.class);
        LightDevice result = (LightDevice) deserialized;
        assertThat(result.isOn()).isTrue();
        assertThat(result.brightness()).hasValue(150);
    }

    // --- Error case: completely unresolvable type ID ---

    @Test
    void completelyUnknownTypeIdThrows() {
        String json = """
                {
                    "@deviceType": "UNKNOWN:SomeDevice",
                    "deviceId": "x-1",
                    "deviceClass": "SWITCH",
                    "label": "Bad Device",
                    "available": true,
                    "lastUpdated": "2026-06-07T10:00:00Z",
                    "tenancyId": "t1",
                    "providerId": "test"
                }
                """;

        assertThatThrownBy(() -> mapper.readValue(json, DeviceEntity.class))
                .isInstanceOf(JsonMappingException.class)
                .hasMessageContaining("UNKNOWN:SomeDevice");
    }

    @Test
    void malformedTypeIdWithoutColonThrows() {
        String json = """
                {
                    "@deviceType": "SwitchDevice",
                    "deviceId": "x-2",
                    "deviceClass": "SWITCH",
                    "label": "Bad Device",
                    "available": true,
                    "lastUpdated": "2026-06-07T10:00:00Z",
                    "tenancyId": "t1",
                    "providerId": "test"
                }
                """;

        assertThatThrownBy(() -> mapper.readValue(json, DeviceEntity.class))
                .isInstanceOf(JsonMappingException.class);
    }

    // --- Vendor type registration ---

    @Test
    void registeredVendorTypeResolvesExactly() throws Exception {
        // Register a test supplement type
        DeviceTypeIdResolver.registerType("LIGHT:TestVendorLight", TestVendorLight.class);

        try {
            String json = """
                    {
                        "@deviceType": "LIGHT:TestVendorLight",
                        "deviceId": "lt-v1",
                        "deviceClass": "LIGHT",
                        "label": "Vendor Light",
                        "available": true,
                        "lastUpdated": "2026-06-07T10:00:00Z",
                        "tenancyId": "t1",
                        "providerId": "test",
                        "on": true
                    }
                    """;

            DeviceEntity deserialized = mapper.readValue(json, DeviceEntity.class);
            assertThat(deserialized).isInstanceOf(TestVendorLight.class);
            assertThat(deserialized.deviceId()).isEqualTo("lt-v1");
        } finally {
            // Clean up to avoid test pollution
            DeviceTypeIdResolver.deregisterType("LIGHT:TestVendorLight");
        }
    }

    // --- StateChangeEvent round-trip ---

    @Test
    void stateChangeEventRoundTrip() throws Exception {
        SwitchDevice before = SwitchDevice.builder()
                .deviceId("sw-1").deviceClass(DeviceClass.SWITCH).label("Kitchen Switch")
                .available(true).lastUpdated(NOW).tenancyId("t1").providerId("test")
                .on(false)
                .build();

        SwitchDevice after = SwitchDevice.builder()
                .deviceId("sw-1").deviceClass(DeviceClass.SWITCH).label("Kitchen Switch")
                .available(true).lastUpdated(NOW).tenancyId("t1").providerId("test")
                .on(true)
                .build();

        StateChangeEvent event = new StateChangeEvent(
                before, after,
                Set.of("isOn"),
                NOW,
                "homeassistant"
        );

        String json = mapper.writeValueAsString(event);
        assertThat(json).contains("\"@deviceType\":\"SWITCH:SwitchDevice\"");

        StateChangeEvent deserialized = mapper.readValue(json, StateChangeEvent.class);
        assertThat(deserialized.before()).isInstanceOf(SwitchDevice.class);
        assertThat(deserialized.after()).isInstanceOf(SwitchDevice.class);
        assertThat(((SwitchDevice) deserialized.before()).isOn()).isFalse();
        assertThat(((SwitchDevice) deserialized.after()).isOn()).isTrue();
        assertThat(deserialized.changedCapabilities()).containsExactly("isOn");
        assertThat(deserialized.occurredAt()).isEqualTo(NOW);
        assertThat(deserialized.providerId()).isEqualTo("homeassistant");
    }

    // --- Compound ID format verification ---

    static Stream<Arguments> allDeviceTypesAndExpectedIds() {
        return Stream.of(
                Arguments.of(DeviceClass.SWITCH, "SwitchDevice"),
                Arguments.of(DeviceClass.LIGHT, "LightDevice"),
                Arguments.of(DeviceClass.THERMOSTAT, "ThermostatDevice"),
                Arguments.of(DeviceClass.SENSOR, "SensorDevice"),
                Arguments.of(DeviceClass.PRESENCE_SENSOR, "PresenceSensor"),
                Arguments.of(DeviceClass.POWER_SENSOR, "PowerSensor"),
                Arguments.of(DeviceClass.LOCK, "LockDevice"),
                Arguments.of(DeviceClass.COVER, "CoverDevice"),
                Arguments.of(DeviceClass.MEDIA_PLAYER, "MediaPlayerDevice"),
                Arguments.of(DeviceClass.FAN, "FanDevice")
        );
    }

    @ParameterizedTest
    @MethodSource("allDeviceTypesAndExpectedIds")
    void compoundIdFormatIsCorrect(DeviceClass deviceClass, String expectedClassName) {
        String expectedId = deviceClass.name() + ":" + expectedClassName;
        assertThat(DeviceTypeIdResolver.isRegistered(expectedId)).isTrue();
    }

    // --- Test helper type for vendor registration test ---

    @com.fasterxml.jackson.databind.annotation.JsonDeserialize(builder = TestVendorLight.Builder.class)
    static class TestVendorLight extends LightDevice {
        TestVendorLight(Builder builder) {
            super(builder);
        }

        @com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder(withPrefix = "")
        static class Builder extends LightDevice.AbstractBuilder<TestVendorLight, Builder> {
            @Override
            protected Builder self() {
                return this;
            }

            @Override
            public TestVendorLight build() {
                return new TestVendorLight(this);
            }
        }
    }
}
