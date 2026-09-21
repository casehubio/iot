package io.casehub.iot.webapp.app.simulation;

import io.casehub.iot.api.DeviceClass;
import io.casehub.iot.api.DeviceEntity;
import io.casehub.iot.api.LightDevice;
import io.casehub.iot.api.PresenceSensor;
import io.casehub.iot.api.SensorDevice;
import io.casehub.iot.api.SensorType;
import io.casehub.iot.api.StateChangeEvent;
import io.casehub.iot.api.Temperature;
import io.casehub.iot.api.ThermostatDevice;
import io.casehub.iot.api.ThermostatMode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

public class SimulationPayloadConverter {

    private static final String PROVIDER_ID = "simulation";

    public StateChangeEvent convert(Map<String, Object> payload) {
        String deviceId = (String) payload.get("deviceId");
        DeviceClass deviceClass = DeviceClass.valueOf((String) payload.get("deviceClass"));
        DeviceEntity after = buildDevice(deviceId, deviceClass, payload);
        Set<String> changed = after.capabilities().keySet();
        return new StateChangeEvent(null, after, changed, Instant.now(), PROVIDER_ID);
    }

    @SuppressWarnings("unchecked")
    private DeviceEntity buildDevice(String deviceId, DeviceClass deviceClass,
                                     Map<String, Object> payload) {
        String tenancyId = (String) payload.getOrDefault("tenancyId", "default-tenant");
        return switch (deviceClass) {
            case PRESENCE_SENSOR -> PresenceSensor.builder()
                    .deviceId(deviceId).deviceClass(deviceClass).label(deviceId)
                    .available(true).lastUpdated(Instant.now())
                    .tenancyId(tenancyId).providerId(PROVIDER_ID)
                    .present(Boolean.TRUE.equals(payload.get("present")))
                    .lastSeen(Instant.now())
                    .build();
            case LIGHT -> new LightDevice.Builder()
                    .deviceId(deviceId).deviceClass(deviceClass).label(deviceId)
                    .available(true).lastUpdated(Instant.now())
                    .tenancyId(tenancyId).providerId(PROVIDER_ID)
                    .on(Boolean.TRUE.equals(payload.get("on")))
                    .brightness(payload.containsKey("brightness")
                            ? ((Number) payload.get("brightness")).intValue() : null)
                    .colorTemp(payload.containsKey("colorTemp")
                            ? ((Number) payload.get("colorTemp")).intValue() : null)
                    .build();
            case THERMOSTAT -> {
                Temperature currentTemp = parseTemperature(
                        (Map<String, Object>) payload.get("currentTemperature"));
                Temperature targetTemp = parseTemperature(
                        (Map<String, Object>) payload.get("targetTemperature"));
                ThermostatMode mode = payload.containsKey("mode")
                        ? ThermostatMode.valueOf((String) payload.get("mode"))
                        : ThermostatMode.AUTO;
                if (currentTemp == null) currentTemp = targetTemp;
                if (targetTemp == null) targetTemp = currentTemp;
                yield new ThermostatDevice.Builder()
                        .deviceId(deviceId).deviceClass(deviceClass).label(deviceId)
                        .available(true).lastUpdated(Instant.now())
                        .tenancyId(tenancyId).providerId(PROVIDER_ID)
                        .currentTemperature(currentTemp)
                        .targetTemperature(targetTemp)
                        .mode(mode)
                        .build();
            }
            case SENSOR -> SensorDevice.builder()
                    .deviceId(deviceId).deviceClass(deviceClass).label(deviceId)
                    .available(true).lastUpdated(Instant.now())
                    .tenancyId(tenancyId).providerId(PROVIDER_ID)
                    .sensorType(payload.containsKey("sensorType")
                            ? SensorType.valueOf((String) payload.get("sensorType"))
                            : SensorType.GENERIC)
                    .numericValue(payload.containsKey("numericValue")
                            ? BigDecimal.valueOf(((Number) payload.get("numericValue")).doubleValue())
                            : null)
                    .build();
            default -> throw new IllegalArgumentException(
                    "Unsupported device class for simulation: " + deviceClass);
        };
    }

    @SuppressWarnings("unchecked")
    private Temperature parseTemperature(Map<String, Object> map) {
        if (map == null) return null;
        BigDecimal value = BigDecimal.valueOf(((Number) map.get("value")).doubleValue());
        Temperature.TemperatureUnit unit = Temperature.TemperatureUnit.valueOf(
                (String) map.get("unit"));
        return new Temperature(value, unit);
    }
}
