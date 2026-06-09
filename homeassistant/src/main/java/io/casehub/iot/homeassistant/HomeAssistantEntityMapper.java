package io.casehub.iot.homeassistant;

import io.casehub.iot.api.*;
import io.casehub.iot.homeassistant.internal.HaStateDto;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;

@ApplicationScoped
public class HomeAssistantEntityMapper {

    private static final Logger LOG = Logger.getLogger(HomeAssistantEntityMapper.class);
    private static final Set<String> PRESENCE_DEVICE_CLASSES = Set.of("occupancy", "motion", "presence");
    private static final Set<String> POWER_DEVICE_CLASSES = Set.of("power", "energy");

    private final HomeAssistantConfig config;

    @Inject
    public HomeAssistantEntityMapper(HomeAssistantConfig config) {
        this.config = config;
    }

    public List<DeviceEntity> mapAll(List<HaStateDto> states) {
        return states.stream()
                .map(this::mapOne)
                .filter(Objects::nonNull)
                .toList();
    }

    public DeviceEntity mapOne(HaStateDto state) {
        try {
            return mapOneUnsafe(state);
        } catch (Exception e) {
            LOG.warnf("Failed to map entity %s — skipping: %s", state.entityId(), e.getMessage());
            return null;
        }
    }

    private DeviceEntity mapOneUnsafe(HaStateDto state) {
        String entityId = state.entityId();
        int dotIndex = entityId.indexOf('.');
        if (dotIndex < 0) {
            LOG.warnf("Unrecognised entity_id format (no domain prefix): %s", entityId);
            return null;
        }
        String domain = entityId.substring(0, dotIndex);
        Map<String, Object> attrs = state.attributes() != null ? state.attributes() : Map.of();
        boolean available = !"unavailable".equals(state.state()) && !"unknown".equals(state.state());
        String label = attrs.containsKey("friendly_name")
                ? (String) attrs.get("friendly_name")
                : entityId;
        Instant lastUpdated = parseInstant(state.lastUpdated(), entityId, "lastUpdated");
        if (lastUpdated == null) {
            return null;
        }
        String deviceClass = attrs.containsKey("device_class")
                ? (String) attrs.get("device_class")
                : null;

        return switch (domain) {
            case "switch" -> mapSwitch(state, entityId, label, available, lastUpdated);
            case "light" -> mapLight(state, attrs, entityId, label, available, lastUpdated);
            case "climate" -> mapClimate(state, attrs, entityId, label, available, lastUpdated);
            case "lock" -> mapLock(state, attrs, entityId, label, available, lastUpdated);
            case "cover" -> mapCover(state, attrs, entityId, label, available, lastUpdated);
            case "media_player" -> mapMediaPlayer(state, attrs, entityId, label, available, lastUpdated);
            case "fan" -> mapFan(state, attrs, entityId, label, available, lastUpdated);
            case "sensor" -> mapSensor(state, attrs, entityId, label, available, lastUpdated, deviceClass);
            case "binary_sensor" -> mapBinarySensor(state, attrs, entityId, label, available, lastUpdated, deviceClass);
            default -> {
                LOG.warnf("Unknown HA domain '%s' for entity %s — skipping", domain, entityId);
                yield null;
            }
        };
    }

    // ---- domain mappers ----

    private SwitchDevice mapSwitch(HaStateDto state, String entityId, String label,
                                   boolean available, Instant lastUpdated) {
        return SwitchDevice.builder()
                .deviceId(entityId).deviceClass(DeviceClass.SWITCH).label(label)
                .available(available).lastUpdated(lastUpdated).tenancyId(config.tenancyId())
                .on("on".equals(state.state()))
                .build();
    }

    private HomeAssistantLight mapLight(HaStateDto state, Map<String, Object> attrs,
                                        String entityId, String label,
                                        boolean available, Instant lastUpdated) {
        var builder = HomeAssistantLight.builder()
                .deviceId(entityId).deviceClass(DeviceClass.LIGHT).label(label)
                .available(available).lastUpdated(lastUpdated).tenancyId(config.tenancyId())
                .on("on".equals(state.state()))
                .brightness(intOrNull(attrs, "brightness"))
                .colorTemp(intOrNull(attrs, "color_temp"));

        if (attrs.containsKey("rgb_color")) {
            @SuppressWarnings("unchecked")
            List<Number> rgb = (List<Number>) attrs.get("rgb_color");
            builder.rgbColor(rgb.stream().mapToInt(Number::intValue).toArray());
        }
        if (attrs.containsKey("effect")) {
            builder.effect((String) attrs.get("effect"));
        }
        if (attrs.containsKey("supported_color_modes")) {
            @SuppressWarnings("unchecked")
            List<String> modes = (List<String>) attrs.get("supported_color_modes");
            builder.supportedColorModes(new LinkedHashSet<>(modes));
        }

        return builder.build();
    }

    private HomeAssistantThermostat mapClimate(HaStateDto state, Map<String, Object> attrs,
                                               String entityId, String label,
                                               boolean available, Instant lastUpdated) {
        Object currentTempRaw = attrs.get("current_temperature");
        Object targetTempRaw = attrs.get("temperature");
        if (currentTempRaw == null || targetTempRaw == null) {
            LOG.warnf("Climate entity %s missing temperature attributes — skipping", entityId);
            return null;
        }

        Temperature.TemperatureUnit tempUnit = "°F".equals(attrs.get("temperature_unit"))
                ? Temperature.TemperatureUnit.FAHRENHEIT
                : Temperature.TemperatureUnit.CELSIUS;

        Temperature currentTemp = new Temperature(
                new BigDecimal(currentTempRaw.toString()), tempUnit);
        Temperature targetTemp = new Temperature(
                new BigDecimal(targetTempRaw.toString()), tempUnit);

        var builder = HomeAssistantThermostat.builder()
                .deviceId(entityId).deviceClass(DeviceClass.THERMOSTAT).label(label)
                .available(available).lastUpdated(lastUpdated).tenancyId(config.tenancyId())
                .currentTemperature(currentTemp)
                .targetTemperature(targetTemp)
                .mode(mapHvacMode(attrs));

        if (attrs.containsKey("preset_mode")) {
            builder.presetMode((String) attrs.get("preset_mode"));
        }
        if (attrs.containsKey("swing_mode")) {
            builder.swingMode((String) attrs.get("swing_mode"));
        }
        if (attrs.containsKey("hvac_action")) {
            builder.hvacAction((String) attrs.get("hvac_action"));
        }

        return builder.build();
    }

    private HomeAssistantLock mapLock(HaStateDto state, Map<String, Object> attrs,
                                      String entityId, String label,
                                      boolean available, Instant lastUpdated) {
        var builder = HomeAssistantLock.builder()
                .deviceId(entityId).deviceClass(DeviceClass.LOCK).label(label)
                .available(available).lastUpdated(lastUpdated).tenancyId(config.tenancyId())
                .locked("locked".equals(state.state()));

        if (attrs.containsKey("changed_by")) {
            builder.changedBy((String) attrs.get("changed_by"));
        }
        if (attrs.containsKey("code_slot")) {
            builder.codeSlot(((Number) attrs.get("code_slot")).intValue());
        }

        return builder.build();
    }

    private CoverDevice mapCover(HaStateDto state, Map<String, Object> attrs,
                                  String entityId, String label,
                                  boolean available, Instant lastUpdated) {
        return new CoverDevice.Builder()
                .deviceId(entityId).deviceClass(DeviceClass.COVER).label(label)
                .available(available).lastUpdated(lastUpdated).tenancyId(config.tenancyId())
                .position(intOrNull(attrs, "current_position"))
                .moving("opening".equals(state.state()) || "closing".equals(state.state()))
                .build();
    }

    private MediaPlayerDevice mapMediaPlayer(HaStateDto state, Map<String, Object> attrs,
                                              String entityId, String label,
                                              boolean available, Instant lastUpdated) {
        Integer volume = null;
        if (attrs.containsKey("volume_level")) {
            volume = (int) (((Number) attrs.get("volume_level")).doubleValue() * 100);
        }

        return MediaPlayerDevice.builder()
                .deviceId(entityId).deviceClass(DeviceClass.MEDIA_PLAYER).label(label)
                .available(available).lastUpdated(lastUpdated).tenancyId(config.tenancyId())
                .playing("playing".equals(state.state()))
                .volume(volume)
                .build();
    }

    private FanDevice mapFan(HaStateDto state, Map<String, Object> attrs,
                              String entityId, String label,
                              boolean available, Instant lastUpdated) {
        Integer speed = null;
        if (attrs.containsKey("percentage")) {
            speed = ((Number) attrs.get("percentage")).intValue();
        }

        return FanDevice.builder()
                .deviceId(entityId).deviceClass(DeviceClass.FAN).label(label)
                .available(available).lastUpdated(lastUpdated).tenancyId(config.tenancyId())
                .on("on".equals(state.state()))
                .speed(speed)
                .build();
    }

    private DeviceEntity mapSensor(HaStateDto state, Map<String, Object> attrs,
                                    String entityId, String label,
                                    boolean available, Instant lastUpdated,
                                    String deviceClass) {
        if (deviceClass != null && POWER_DEVICE_CLASSES.contains(deviceClass)) {
            return mapPowerSensor(state, entityId, label, available, lastUpdated, deviceClass);
        }
        if (deviceClass != null && PRESENCE_DEVICE_CLASSES.contains(deviceClass)) {
            return mapPresenceSensor(state, entityId, label, available, lastUpdated);
        }
        return mapGenericSensor(state, attrs, entityId, label, available, lastUpdated, deviceClass, false);
    }

    private DeviceEntity mapBinarySensor(HaStateDto state, Map<String, Object> attrs,
                                          String entityId, String label,
                                          boolean available, Instant lastUpdated,
                                          String deviceClass) {
        if (deviceClass != null && PRESENCE_DEVICE_CLASSES.contains(deviceClass)) {
            return mapPresenceSensor(state, entityId, label, available, lastUpdated);
        }
        return mapGenericSensor(state, attrs, entityId, label, available, lastUpdated, deviceClass, true);
    }

    private PowerSensor mapPowerSensor(HaStateDto state, String entityId, String label,
                                        boolean available, Instant lastUpdated,
                                        String deviceClass) {
        BigDecimal value = available ? parseOrNull(state.state()) : null;
        var builder = PowerSensor.builder()
                .deviceId(entityId).deviceClass(DeviceClass.POWER_SENSOR).label(label)
                .available(available).lastUpdated(lastUpdated).tenancyId(config.tenancyId());

        if ("power".equals(deviceClass)) {
            builder.power(value);
        } else {
            builder.energy(value);
        }

        return builder.build();
    }

    private PresenceSensor mapPresenceSensor(HaStateDto state, String entityId, String label,
                                              boolean available, Instant lastUpdated) {
        Instant lastSeen = parseInstant(state.lastChanged(), entityId, "lastChanged");
        if (lastSeen == null) {
            return null;
        }
        return PresenceSensor.builder()
                .deviceId(entityId).deviceClass(DeviceClass.PRESENCE_SENSOR).label(label)
                .available(available).lastUpdated(lastUpdated).tenancyId(config.tenancyId())
                .present("on".equals(state.state()))
                .lastSeen(lastSeen)
                .build();
    }

    private SensorDevice mapGenericSensor(HaStateDto state, Map<String, Object> attrs,
                                           String entityId, String label,
                                           boolean available, Instant lastUpdated,
                                           String deviceClass, boolean binary) {
        SensorType sensorType = mapSensorType(deviceClass);

        var builder = SensorDevice.builder()
                .deviceId(entityId).deviceClass(DeviceClass.SENSOR).label(label)
                .available(available).lastUpdated(lastUpdated).tenancyId(config.tenancyId())
                .sensorType(sensorType);

        if (binary) {
            builder.binaryValue("on".equals(state.state()));
        } else {
            builder.numericValue(available ? parseOrNull(state.state()) : null);
            builder.unit((String) attrs.get("unit_of_measurement"));
        }

        return builder.build();
    }

    // ---- helper methods ----

    private ThermostatMode mapHvacMode(Map<String, Object> attrs) {
        String hvacMode = (String) attrs.get("hvac_mode");
        if (hvacMode == null) {
            LOG.warn("No hvac_mode attribute — defaulting to OFF");
            return ThermostatMode.OFF;
        }
        return switch (hvacMode) {
            case "heat" -> ThermostatMode.HEAT;
            case "cool" -> ThermostatMode.COOL;
            case "heat_cool", "auto" -> ThermostatMode.AUTO;
            case "off" -> ThermostatMode.OFF;
            case "fan_only" -> ThermostatMode.FAN_ONLY;
            case "dry" -> ThermostatMode.DRY;
            default -> {
                LOG.warnf("Unknown hvac_mode '%s' — defaulting to OFF", hvacMode);
                yield ThermostatMode.OFF;
            }
        };
    }

    private SensorType mapSensorType(String deviceClass) {
        if (deviceClass == null) return SensorType.GENERIC;
        return switch (deviceClass) {
            case "temperature" -> SensorType.TEMPERATURE;
            case "humidity" -> SensorType.HUMIDITY;
            case "motion" -> SensorType.MOTION;
            case "door", "window", "garage_door" -> SensorType.DOOR_WINDOW;
            case "carbon_dioxide" -> SensorType.CO2;
            case "carbon_monoxide" -> SensorType.CO;
            case "illuminance" -> SensorType.LUX;
            default -> SensorType.GENERIC;
        };
    }

    private Instant parseInstant(String value, String entityId, String field) {
        if (value == null) {
            LOG.warnf("Entity %s has null %s — skipping", entityId, field);
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            LOG.warnf("Entity %s has malformed %s '%s' — skipping", entityId, field, value);
            return null;
        }
    }

    private static BigDecimal parseOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer intOrNull(Map<String, Object> attrs, String key) {
        Object v = attrs.get(key);
        return v != null ? ((Number) v).intValue() : null;
    }
}
