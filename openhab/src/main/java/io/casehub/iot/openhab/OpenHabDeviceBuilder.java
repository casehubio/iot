package io.casehub.iot.openhab;

import io.casehub.iot.api.*;

import java.math.BigDecimal;

/**
 * Constructs the correct {@link DeviceEntity} subtype from {@link ResolvedDeviceFields}.
 *
 * <p>This is the single point of device construction for the OpenHAB provider.
 * Both Equipment-based and Thing-based resolution strategies produce a
 * {@code ResolvedDeviceFields}, and this builder dispatches on {@code deviceClass}
 * to create the correct entity type — including vendor supplement types
 * ({@link OpenHabThermostat}, {@link OpenHabLight}, {@link OpenHabRollershutter})
 * when OpenHAB-specific fields are present.</p>
 */
public final class OpenHabDeviceBuilder {

    private static final Temperature ZERO_CELSIUS =
            new Temperature(BigDecimal.ZERO, Temperature.TemperatureUnit.CELSIUS);

    private OpenHabDeviceBuilder() {}

    /**
     * Builds a {@link DeviceEntity} from resolved fields, dispatching on device class.
     *
     * @param f the resolved fields from either Equipment or Thing resolution
     * @return the constructed device entity
     */
    public static DeviceEntity build(ResolvedDeviceFields f) {
        return switch (f.deviceClass()) {
            case THERMOSTAT -> buildThermostat(f);
            case LIGHT -> buildLight(f);
            case SWITCH -> buildSwitch(f);
            case LOCK -> buildLock(f);
            case COVER -> buildCover(f);
            case MEDIA_PLAYER -> buildMediaPlayer(f);
            case FAN -> buildFan(f);
            case SENSOR -> buildSensor(f);
            case POWER_SENSOR -> buildPowerSensor(f);
            case PRESENCE_SENSOR -> buildPresenceSensor(f);
            case CAMERA -> buildCamera(f);
        };
    }

    // ---- thermostat ----

    private static DeviceEntity buildThermostat(ResolvedDeviceFields f) {
        Temperature currentTemp = f.currentTemperature() != null ? f.currentTemperature() : ZERO_CELSIUS;
        Temperature targetTemp = f.targetTemperature() != null ? f.targetTemperature() : ZERO_CELSIUS;
        ThermostatMode mode = f.mode() != null ? f.mode() : ThermostatMode.OFF;

        if (f.heatingDemand() != null || f.coolingDemand() != null) {
            return OpenHabThermostat.builder()
                    .deviceId(f.deviceId()).deviceClass(DeviceClass.THERMOSTAT).label(f.label())
                    .available(f.available()).lastUpdated(f.now()).tenancyId(f.tenancyId()).providerId("openhab")
                    .currentTemperature(currentTemp).targetTemperature(targetTemp).mode(mode)
                    .heatingDemand(f.heatingDemand()).coolingDemand(f.coolingDemand())
                    .build();
        }

        return new ThermostatDevice.Builder()
                .deviceId(f.deviceId()).deviceClass(DeviceClass.THERMOSTAT).label(f.label())
                .available(f.available()).lastUpdated(f.now()).tenancyId(f.tenancyId()).providerId("openhab")
                .currentTemperature(currentTemp).targetTemperature(targetTemp).mode(mode)
                .build();
    }

    // ---- light ----

    private static DeviceEntity buildLight(ResolvedDeviceFields f) {
        boolean on = f.on() != null ? f.on() : false;

        if (f.hsb() != null) {
            return OpenHabLight.builder()
                    .deviceId(f.deviceId()).deviceClass(DeviceClass.LIGHT).label(f.label())
                    .available(f.available()).lastUpdated(f.now()).tenancyId(f.tenancyId()).providerId("openhab")
                    .on(on).brightness(f.brightness()).hsb(f.hsb())
                    .build();
        }

        return new LightDevice.Builder()
                .deviceId(f.deviceId()).deviceClass(DeviceClass.LIGHT).label(f.label())
                .available(f.available()).lastUpdated(f.now()).tenancyId(f.tenancyId()).providerId("openhab")
                .on(on)
                .build();
    }

    // ---- switch ----

    private static SwitchDevice buildSwitch(ResolvedDeviceFields f) {
        boolean on = f.on() != null ? f.on() : false;

        return SwitchDevice.builder()
                .deviceId(f.deviceId()).deviceClass(DeviceClass.SWITCH).label(f.label())
                .available(f.available()).lastUpdated(f.now()).tenancyId(f.tenancyId()).providerId("openhab")
                .on(on)
                .build();
    }

    // ---- lock ----

    private static LockDevice buildLock(ResolvedDeviceFields f) {
        boolean locked = f.locked() != null ? f.locked() : false;

        return new LockDevice.Builder()
                .deviceId(f.deviceId()).deviceClass(DeviceClass.LOCK).label(f.label())
                .available(f.available()).lastUpdated(f.now()).tenancyId(f.tenancyId()).providerId("openhab")
                .locked(locked)
                .build();
    }

    // ---- cover ----

    private static DeviceEntity buildCover(ResolvedDeviceFields f) {
        if (f.isRollershutter()) {
            return OpenHabRollershutter.builder()
                    .deviceId(f.deviceId()).deviceClass(DeviceClass.COVER).label(f.label())
                    .available(f.available()).lastUpdated(f.now()).tenancyId(f.tenancyId()).providerId("openhab")
                    .position(f.position()).moving(false)
                    .build();
        }

        return new CoverDevice.Builder()
                .deviceId(f.deviceId()).deviceClass(DeviceClass.COVER).label(f.label())
                .available(f.available()).lastUpdated(f.now()).tenancyId(f.tenancyId()).providerId("openhab")
                .position(f.position()).moving(false)
                .build();
    }

    // ---- media player ----

    private static MediaPlayerDevice buildMediaPlayer(ResolvedDeviceFields f) {
        return MediaPlayerDevice.builder()
                .deviceId(f.deviceId()).deviceClass(DeviceClass.MEDIA_PLAYER).label(f.label())
                .available(f.available()).lastUpdated(f.now()).tenancyId(f.tenancyId()).providerId("openhab")
                .volume(f.volume())
                .build();
    }

    // ---- fan ----

    private static FanDevice buildFan(ResolvedDeviceFields f) {
        boolean on = f.on() != null ? f.on() : false;

        return FanDevice.builder()
                .deviceId(f.deviceId()).deviceClass(DeviceClass.FAN).label(f.label())
                .available(f.available()).lastUpdated(f.now()).tenancyId(f.tenancyId()).providerId("openhab")
                .on(on)
                .build();
    }

    // ---- sensor ----

    private static SensorDevice buildSensor(ResolvedDeviceFields f) {
        SensorType sensorType = f.sensorType() != null ? f.sensorType() : SensorType.GENERIC;

        return SensorDevice.builder()
                .deviceId(f.deviceId()).deviceClass(DeviceClass.SENSOR).label(f.label())
                .available(f.available()).lastUpdated(f.now()).tenancyId(f.tenancyId()).providerId("openhab")
                .sensorType(sensorType).numericValue(f.numericValue()).unit(f.unit())
                .build();
    }

    // ---- power sensor ----

    private static PowerSensor buildPowerSensor(ResolvedDeviceFields f) {
        return PowerSensor.builder()
                .deviceId(f.deviceId()).deviceClass(DeviceClass.POWER_SENSOR).label(f.label())
                .available(f.available()).lastUpdated(f.now()).tenancyId(f.tenancyId()).providerId("openhab")
                .power(f.power()).energy(f.energy())
                .build();
    }

    // ---- camera ----

    private static CameraDevice buildCamera(ResolvedDeviceFields f) {
        boolean streaming = f.streaming() != null ? f.streaming() : false;

        return CameraDevice.builder()
                .deviceId(f.deviceId()).deviceClass(DeviceClass.CAMERA).label(f.label())
                .available(f.available()).lastUpdated(f.now()).tenancyId(f.tenancyId()).providerId("openhab")
                .streaming(streaming)
                .build();
    }

    // ---- presence sensor ----

    private static PresenceSensor buildPresenceSensor(ResolvedDeviceFields f) {
        boolean present = f.present() != null ? f.present() : false;

        return PresenceSensor.builder()
                .deviceId(f.deviceId()).deviceClass(DeviceClass.PRESENCE_SENSOR).label(f.label())
                .available(f.available()).lastUpdated(f.now()).tenancyId(f.tenancyId()).providerId("openhab")
                .present(present).lastSeen(f.now())
                .build();
    }
}
