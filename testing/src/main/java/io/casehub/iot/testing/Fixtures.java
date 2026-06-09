package io.casehub.iot.testing;

import io.casehub.iot.api.CoverDevice;
import io.casehub.iot.api.DeviceClass;
import io.casehub.iot.api.DeviceEntity;
import io.casehub.iot.api.FanDevice;
import io.casehub.iot.api.LightDevice;
import io.casehub.iot.api.LockDevice;
import io.casehub.iot.api.MediaPlayerDevice;
import io.casehub.iot.api.PowerSensor;
import io.casehub.iot.api.PresenceSensor;
import io.casehub.iot.api.SensorDevice;
import io.casehub.iot.api.SensorType;
import io.casehub.iot.api.SwitchDevice;
import io.casehub.iot.api.Temperature;
import io.casehub.iot.api.ThermostatDevice;
import io.casehub.iot.api.ThermostatMode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class Fixtures {

    public static final String DEFAULT_TENANT = "default-tenant";
    public static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");

    private Fixtures() {}

    public static SwitchDevice hallwaySwitch() {
        return SwitchDevice.builder()
            .deviceId("switch-hallway-1").deviceClass(DeviceClass.SWITCH)
            .label("Hallway Switch").available(true).lastUpdated(EPOCH)
            .tenancyId(DEFAULT_TENANT).on(false).build();
    }

    public static LightDevice livingRoomLight() {
        return new LightDevice.Builder()
            .deviceId("light-living-1").deviceClass(DeviceClass.LIGHT)
            .label("Living Room Light").available(true).lastUpdated(EPOCH)
            .tenancyId(DEFAULT_TENANT).on(false).build();
    }

    public static ThermostatDevice livingRoomThermostat() {
        return new ThermostatDevice.Builder()
            .deviceId("thermostat-living-1").deviceClass(DeviceClass.THERMOSTAT)
            .label("Living Room Thermostat").available(true).lastUpdated(EPOCH)
            .tenancyId(DEFAULT_TENANT)
            .currentTemperature(new Temperature(new BigDecimal("21"), Temperature.TemperatureUnit.CELSIUS))
            .targetTemperature(new Temperature(new BigDecimal("22"), Temperature.TemperatureUnit.CELSIUS))
            .mode(ThermostatMode.HEAT).build();
    }

    public static SensorDevice outdoorTemperature() {
        return SensorDevice.builder()
            .deviceId("sensor-outdoor-1").deviceClass(DeviceClass.SENSOR)
            .label("Outdoor Temperature").available(true).lastUpdated(EPOCH)
            .tenancyId(DEFAULT_TENANT)
            .sensorType(SensorType.TEMPERATURE)
            .numericValue(new BigDecimal("15")).unit("C").build();
    }

    public static PresenceSensor frontDoorPresence() {
        return PresenceSensor.builder()
            .deviceId("presence-front-1").deviceClass(DeviceClass.PRESENCE_SENSOR)
            .label("Front Door Presence").available(true).lastUpdated(EPOCH)
            .tenancyId(DEFAULT_TENANT).present(false).lastSeen(EPOCH).build();
    }

    public static PowerSensor solarPanel() {
        return PowerSensor.builder()
            .deviceId("power-solar-1").deviceClass(DeviceClass.POWER_SENSOR)
            .label("Solar Panel").available(true).lastUpdated(EPOCH)
            .tenancyId(DEFAULT_TENANT)
            .power(new BigDecimal("3200")).build();
    }

    public static LockDevice frontDoorLock() {
        return new LockDevice.Builder()
            .deviceId("lock-front-1").deviceClass(DeviceClass.LOCK)
            .label("Front Door Lock").available(true).lastUpdated(EPOCH)
            .tenancyId(DEFAULT_TENANT).locked(true).build();
    }

    public static CoverDevice bedroomBlinds() {
        return new CoverDevice.Builder()
            .deviceId("cover-bedroom-1").deviceClass(DeviceClass.COVER)
            .label("Bedroom Blinds").available(true).lastUpdated(EPOCH)
            .tenancyId(DEFAULT_TENANT).moving(false).build();
    }

    public static MediaPlayerDevice livingRoomSpeaker() {
        return MediaPlayerDevice.builder()
            .deviceId("media-living-1").deviceClass(DeviceClass.MEDIA_PLAYER)
            .label("Living Room Speaker").available(true).lastUpdated(EPOCH)
            .tenancyId(DEFAULT_TENANT).playing(false).build();
    }

    public static FanDevice bedroomFan() {
        return FanDevice.builder()
            .deviceId("fan-bedroom-1").deviceClass(DeviceClass.FAN)
            .label("Bedroom Fan").available(true).lastUpdated(EPOCH)
            .tenancyId(DEFAULT_TENANT).on(false).build();
    }

    public static List<DeviceEntity> standardHome() {
        return List.of(
            hallwaySwitch(), livingRoomLight(), livingRoomThermostat(),
            outdoorTemperature(), frontDoorPresence(), solarPanel(),
            frontDoorLock(), bedroomBlinds(), livingRoomSpeaker(), bedroomFan());
    }
}
