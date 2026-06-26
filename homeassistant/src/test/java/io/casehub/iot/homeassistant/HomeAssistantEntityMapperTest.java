package io.casehub.iot.homeassistant;

import io.casehub.iot.api.*;
import io.casehub.iot.homeassistant.internal.HaStateDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class HomeAssistantEntityMapperTest {

    private final HomeAssistantEntityMapper mapper = new HomeAssistantEntityMapper("t1");

    private HaStateDto dto(String entityId, String state, Map<String, Object> attrs) {
        return new HaStateDto(entityId, state, "2026-06-09T10:00:00Z", "2026-06-09T09:55:00Z", attrs);
    }

    // --- 1. Switch ---

    @Test
    void mapsSwitch() {
        var result = mapper.mapOne(dto("switch.hallway", "on", Map.of()));

        assertThat(result).isInstanceOf(SwitchDevice.class);
        var sw = (SwitchDevice) result;
        assertThat(sw.isOn()).isTrue();
        assertThat(sw.deviceClass()).isEqualTo(DeviceClass.SWITCH);
    }

    // --- 2. Light with all supplement fields ---

    @Test
    void mapsLight() {
        var attrs = Map.<String, Object>of(
                "friendly_name", "Kitchen Light",
                "brightness", 200,
                "color_temp", 370,
                "rgb_color", List.of(255, 0, 0),
                "effect", "rainbow",
                "supported_color_modes", List.of("hs", "rgb")
        );
        var result = mapper.mapOne(dto("light.kitchen", "on", attrs));

        assertThat(result).isInstanceOf(HomeAssistantLight.class);
        var light = (HomeAssistantLight) result;
        assertThat(light.isOn()).isTrue();
        assertThat(light.brightness()).hasValue(200);
        assertThat(light.colorTemp()).hasValue(370);
        assertThat(light.rgbColor()).isPresent();
        assertThat(light.rgbColor().get()).containsExactly(255, 0, 0);
        assertThat(light.effect()).hasValue("rainbow");
        assertThat(light.supportedColorModes()).containsExactlyInAnyOrder("hs", "rgb");
        assertThat(light.deviceClass()).isEqualTo(DeviceClass.LIGHT);
    }

    // --- 3. Climate (Celsius) ---

    @Test
    void mapsClimate() {
        var attrs = Map.<String, Object>of(
                "friendly_name", "Living Room",
                "current_temperature", 21.5,
                "temperature", 22.0,
                "hvac_mode", "heat",
                "temperature_unit", "°C",
                "preset_mode", "home"
        );
        var result = mapper.mapOne(dto("climate.living", "heat", attrs));

        assertThat(result).isInstanceOf(HomeAssistantThermostat.class);
        var therm = (HomeAssistantThermostat) result;
        assertThat(therm.currentTemperature().value()).isEqualByComparingTo(new BigDecimal("21.5"));
        assertThat(therm.currentTemperature().unit()).isEqualTo(Temperature.TemperatureUnit.CELSIUS);
        assertThat(therm.targetTemperature().value()).isEqualByComparingTo(new BigDecimal("22.0"));
        assertThat(therm.mode()).isEqualTo(ThermostatMode.HEAT);
        assertThat(therm.presetMode()).hasValue("home");
        assertThat(therm.deviceClass()).isEqualTo(DeviceClass.THERMOSTAT);
    }

    // --- 4. Climate Fahrenheit ---

    @Test
    void mapsClimateFahrenheit() {
        var attrs = Map.<String, Object>of(
                "current_temperature", 72.0,
                "temperature", 74.0,
                "hvac_mode", "cool",
                "temperature_unit", "°F"
        );
        var result = mapper.mapOne(dto("climate.bedroom", "cool", attrs));

        assertThat(result).isInstanceOf(HomeAssistantThermostat.class);
        var therm = (HomeAssistantThermostat) result;
        assertThat(therm.currentTemperature().unit()).isEqualTo(Temperature.TemperatureUnit.FAHRENHEIT);
    }

    // --- 5. Lock ---

    @Test
    void mapsLock() {
        var attrs = Map.<String, Object>of(
                "friendly_name", "Front Door",
                "changed_by", "John",
                "code_slot", 3
        );
        var result = mapper.mapOne(dto("lock.front", "locked", attrs));

        assertThat(result).isInstanceOf(HomeAssistantLock.class);
        var lock = (HomeAssistantLock) result;
        assertThat(lock.isLocked()).isTrue();
        assertThat(lock.changedBy()).hasValue("John");
        assertThat(lock.codeSlot()).hasValue(3);
        assertThat(lock.deviceClass()).isEqualTo(DeviceClass.LOCK);
    }

    // --- 6. Cover with position ---

    @Test
    void mapsCover() {
        var attrs = Map.<String, Object>of(
                "friendly_name", "Blinds",
                "current_position", 75
        );
        var result = mapper.mapOne(dto("cover.blinds", "open", attrs));

        assertThat(result).isInstanceOf(CoverDevice.class);
        var cover = (CoverDevice) result;
        assertThat(cover.position()).hasValue(75);
        assertThat(cover.isMoving()).isFalse();
        assertThat(cover.deviceClass()).isEqualTo(DeviceClass.COVER);
    }

    // --- 7. Cover without position ---

    @Test
    void mapsCoverWithoutPosition() {
        var result = mapper.mapOne(dto("cover.garage", "closed", Map.of()));

        assertThat(result).isInstanceOf(CoverDevice.class);
        var cover = (CoverDevice) result;
        assertThat(cover.position()).isEmpty();
    }

    // --- 8. Media player playing ---

    @Test
    void mapsMediaPlayer() {
        var attrs = Map.<String, Object>of(
                "friendly_name", "Speaker",
                "volume_level", 0.65
        );
        var result = mapper.mapOne(dto("media_player.speaker", "playing", attrs));

        assertThat(result).isInstanceOf(MediaPlayerDevice.class);
        var mp = (MediaPlayerDevice) result;
        assertThat(mp.isPlaying()).isTrue();
        assertThat(mp.volume()).hasValue(65);
        assertThat(mp.deviceClass()).isEqualTo(DeviceClass.MEDIA_PLAYER);
    }

    // --- 9. Media player paused ---

    @Test
    void mapsMediaPlayerPaused() {
        var result = mapper.mapOne(dto("media_player.speaker", "paused", Map.of()));

        assertThat(result).isInstanceOf(MediaPlayerDevice.class);
        var mp = (MediaPlayerDevice) result;
        assertThat(mp.isPlaying()).isFalse();
    }

    // --- 10. Fan ---

    @Test
    void mapsFan() {
        var attrs = Map.<String, Object>of(
                "friendly_name", "Bedroom Fan",
                "percentage", 50
        );
        var result = mapper.mapOne(dto("fan.bedroom", "on", attrs));

        assertThat(result).isInstanceOf(FanDevice.class);
        var fan = (FanDevice) result;
        assertThat(fan.isOn()).isTrue();
        assertThat(fan.speed()).hasValue(50);
        assertThat(fan.deviceClass()).isEqualTo(DeviceClass.FAN);
    }

    // --- 11. Power sensor (power) ---

    @Test
    void mapsPowerSensorPowerOnly() {
        var attrs = Map.<String, Object>of("device_class", "power");
        var result = mapper.mapOne(dto("sensor.solar_power", "3200", attrs));

        assertThat(result).isInstanceOf(PowerSensor.class);
        var ps = (PowerSensor) result;
        assertThat(ps.power()).hasValue(new BigDecimal("3200"));
        assertThat(ps.energy()).isEmpty();
        assertThat(ps.deviceClass()).isEqualTo(DeviceClass.POWER_SENSOR);
    }

    // --- 12. Power sensor (energy) ---

    @Test
    void mapsPowerSensorEnergyOnly() {
        var attrs = Map.<String, Object>of("device_class", "energy");
        var result = mapper.mapOne(dto("sensor.grid_energy", "15.2", attrs));

        assertThat(result).isInstanceOf(PowerSensor.class);
        var ps = (PowerSensor) result;
        assertThat(ps.power()).isEmpty();
        assertThat(ps.energy()).hasValue(new BigDecimal("15.2"));
    }

    // --- 13. Presence sensor ---

    @Test
    void mapsPresenceSensor() {
        var attrs = Map.<String, Object>of("device_class", "motion");
        var result = mapper.mapOne(dto("binary_sensor.motion", "on", attrs));

        assertThat(result).isInstanceOf(PresenceSensor.class);
        var ps = (PresenceSensor) result;
        assertThat(ps.isPresent()).isTrue();
        assertThat(ps.lastSeen()).isEqualTo(Instant.parse("2026-06-09T09:55:00Z"));
        assertThat(ps.deviceClass()).isEqualTo(DeviceClass.PRESENCE_SENSOR);
    }

    // --- 14. Sensor (temperature) ---

    @Test
    void mapsSensorTemperature() {
        var attrs = Map.<String, Object>of(
                "device_class", "temperature",
                "unit_of_measurement", "°C"
        );
        var result = mapper.mapOne(dto("sensor.outdoor_temp", "18.5", attrs));

        assertThat(result).isInstanceOf(SensorDevice.class);
        var s = (SensorDevice) result;
        assertThat(s.sensorType()).isEqualTo(SensorType.TEMPERATURE);
        assertThat(s.numericValue()).hasValue(new BigDecimal("18.5"));
        assertThat(s.unit()).hasValue("°C");
    }

    // --- 15. Sensor (CO) ---

    @Test
    void mapsSensorCO() {
        var attrs = Map.<String, Object>of("device_class", "carbon_monoxide");
        var result = mapper.mapOne(dto("sensor.co_detector", "0", attrs));

        assertThat(result).isInstanceOf(SensorDevice.class);
        var s = (SensorDevice) result;
        assertThat(s.sensorType()).isEqualTo(SensorType.CO);
    }

    // --- 16. Sensor (CO2) ---

    @Test
    void mapsSensorCO2() {
        var attrs = Map.<String, Object>of("device_class", "carbon_dioxide");
        var result = mapper.mapOne(dto("sensor.co2_level", "420", attrs));

        assertThat(result).isInstanceOf(SensorDevice.class);
        var s = (SensorDevice) result;
        assertThat(s.sensorType()).isEqualTo(SensorType.CO2);
    }

    // --- 17. Binary sensor non-presence ---

    @Test
    void mapsBinarySensorNonPresence() {
        var attrs = Map.<String, Object>of("device_class", "door");
        var result = mapper.mapOne(dto("binary_sensor.front_door", "on", attrs));

        assertThat(result).isInstanceOf(SensorDevice.class);
        var s = (SensorDevice) result;
        assertThat(s.sensorType()).isEqualTo(SensorType.DOOR_WINDOW);
        assertThat(s.binaryValue()).hasValue(true);
        assertThat(s.numericValue()).isEmpty();
        assertThat(s.unit()).isEmpty();
    }

    // --- 18. Unknown domain ---

    @Test
    void skipsUnknownDomain() {
        var result = mapper.mapOne(dto("unknown.x", "on", Map.of()));

        assertThat(result).isNull();
    }

    // --- 19. Unavailable entity ---

    @Test
    void unavailableEntityHasAvailableFalse() {
        var result = mapper.mapOne(dto("switch.hallway", "unavailable", Map.of()));

        assertThat(result).isNotNull();
        assertThat(result.available()).isFalse();
    }

    // --- 20. Common fields ---

    @Test
    void commonFieldsPopulated() {
        var attrs = Map.<String, Object>of("friendly_name", "Hallway Switch");
        var result = mapper.mapOne(dto("switch.hallway", "off", attrs));

        assertThat(result).isNotNull();
        assertThat(result.deviceId()).isEqualTo("switch.hallway");
        assertThat(result.label()).isEqualTo("Hallway Switch");
        assertThat(result.lastUpdated()).isEqualTo(Instant.parse("2026-06-09T10:00:00Z"));
        assertThat(result.tenancyId()).isEqualTo("t1");
    }

    // --- 21. HVAC mode dry ---

    @Test
    void hvacModeDryMapsToDry() {
        var attrs = Map.<String, Object>of(
                "current_temperature", 25.0,
                "temperature", 24.0,
                "hvac_mode", "dry"
        );
        var result = mapper.mapOne(dto("climate.dehumid", "dry", attrs));

        assertThat(result).isInstanceOf(HomeAssistantThermostat.class);
        var therm = (HomeAssistantThermostat) result;
        assertThat(therm.mode()).isEqualTo(ThermostatMode.DRY);
    }

    // --- 22. HVAC mode null defaults to OFF ---

    @Test
    void hvacModeNullDefaultsToOff() {
        var attrs = Map.<String, Object>of(
                "current_temperature", 20.0,
                "temperature", 20.0
        );
        var result = mapper.mapOne(dto("climate.basic", "off", attrs));

        assertThat(result).isInstanceOf(HomeAssistantThermostat.class);
        var therm = (HomeAssistantThermostat) result;
        assertThat(therm.mode()).isEqualTo(ThermostatMode.OFF);
    }

    // --- 23. parseOrNull handles non-numeric ---

    @Test
    void parseOrNullHandlesNonNumeric() {
        var attrs = Map.<String, Object>of(
                "device_class", "temperature",
                "unit_of_measurement", "°C"
        );
        var result = mapper.mapOne(dto("sensor.broken", "unavailable", attrs));

        assertThat(result).isInstanceOf(SensorDevice.class);
        var s = (SensorDevice) result;
        assertThat(s.numericValue()).isEmpty();
        assertThat(s.available()).isFalse();
    }

    // --- 24. Unavailable climate returns null (C1) ---

    @Test
    void unavailableClimateReturnsNull() {
        var result = mapper.mapOne(dto("climate.living", "unavailable", Map.of()));

        assertThat(result).isNull();
    }

    // --- 25. Null lastUpdated returns null (C2) ---

    @Test
    void nullLastUpdatedReturnsNull() {
        var state = new HaStateDto("switch.hallway", "on", null, "2026-06-09T09:55:00Z", Map.of());
        var result = mapper.mapOne(state);

        assertThat(result).isNull();
    }

    // --- 26. Malformed entity_id returns null (I1) ---

    @Test
    void malformedEntityIdReturnsNull() {
        var result = mapper.mapOne(dto("nodot", "on", Map.of()));

        assertThat(result).isNull();
    }

    // --- 27. Sensor domain with presence device_class ---

    @Test
    void sensorDomainPresenceDeviceClass() {
        var attrs = Map.<String, Object>of("device_class", "motion");
        var result = mapper.mapOne(dto("sensor.motion_1", "on", attrs));

        assertThat(result).isInstanceOf(PresenceSensor.class);
        var ps = (PresenceSensor) result;
        assertThat(ps.isPresent()).isTrue();
        assertThat(ps.deviceClass()).isEqualTo(DeviceClass.PRESENCE_SENSOR);
    }

    // --- camera ---

    @Test
    void mapsCameraStreamingState() {
        var result = mapper.mapOne(dto("camera.front_door", "streaming", Map.of()));

        assertThat(result).isInstanceOf(CameraDevice.class);
        var cam = (CameraDevice) result;
        assertThat(cam.isStreaming()).isTrue();
        assertThat(cam.deviceClass()).isEqualTo(DeviceClass.CAMERA);
    }

    @Test
    void mapsCameraIdleState() {
        var result = mapper.mapOne(dto("camera.backyard", "idle", Map.of()));

        assertThat(result).isInstanceOf(CameraDevice.class);
        var cam = (CameraDevice) result;
        assertThat(cam.isStreaming()).isFalse();
    }

    @Test
    void mapsCameraRecordingAsStreaming() {
        var result = mapper.mapOne(dto("camera.garage", "recording", Map.of()));

        assertThat(result).isInstanceOf(CameraDevice.class);
        var cam = (CameraDevice) result;
        assertThat(cam.isStreaming()).isTrue();
    }

    // --- mapAll ---

    @Test
    void mapAllFiltersNulls() {
        var states = List.of(
                dto("switch.hallway", "on", Map.of()),
                dto("unknown.x", "on", Map.of()),
                dto("light.kitchen", "off", Map.of())
        );
        var results = mapper.mapAll(states);

        assertThat(results).hasSize(2);
        assertThat(results.get(0)).isInstanceOf(SwitchDevice.class);
        assertThat(results.get(1)).isInstanceOf(HomeAssistantLight.class);
    }
}
