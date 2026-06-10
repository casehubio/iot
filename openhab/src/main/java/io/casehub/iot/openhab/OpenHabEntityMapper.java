package io.casehub.iot.openhab;

import io.casehub.iot.api.*;
import io.casehub.iot.openhab.internal.OpenHabItemDto;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Maps OpenHAB Equipment Groups (semantic model) to CaseHub {@link DeviceEntity} hierarchy.
 *
 * <p>Unlike Home Assistant where each entity_id maps 1:1 to a DeviceEntity, OpenHAB uses
 * Equipment Groups: one Equipment Group with multiple member Point items maps to a single
 * DeviceEntity. The mapper receives the Equipment Group DTO with inlined members and
 * assembles fields from the Point tags on each member.</p>
 */
@ApplicationScoped
public class OpenHabEntityMapper {

    private static final Logger LOG = Logger.getLogger(OpenHabEntityMapper.class);

    private static final Set<String> HVAC_TAGS = Set.of(
            "HVAC", "RadiatorControl", "AirConditioner", "HeatPump", "Boiler");
    private static final Set<String> LIGHT_TAGS = Set.of("Lightbulb", "LightStrip");
    private static final Set<String> SWITCH_TAGS = Set.of("PowerOutlet", "WallSwitch");
    private static final Set<String> COVER_TAGS = Set.of("Blinds", "Rollershutter");
    private static final Set<String> MEDIA_TAGS = Set.of("Receiver", "Screen", "Speaker", "Television");

    private final String tenancyId;

    @Inject
    public OpenHabEntityMapper(OpenHabConfig config) {
        this.tenancyId = config.tenancyId();
    }

    /** Package-private constructor for unit tests (no CDI). */
    OpenHabEntityMapper(String tenancyId) {
        this.tenancyId = tenancyId;
    }

    /**
     * Maps an OpenHAB Equipment Group to a CaseHub DeviceEntity.
     *
     * @param equipment the Equipment Group DTO with inlined members
     * @param now       timestamp to use as lastUpdated (OpenHAB items have no timestamp)
     * @return the mapped DeviceEntity, or null if the Equipment tag is unrecognised
     */
    public DeviceEntity mapEquipment(OpenHabItemDto equipment, Instant now) {
        try {
            return mapEquipmentUnsafe(equipment, now);
        } catch (Exception e) {
            LOG.warnf("Failed to map equipment %s — skipping: %s", equipment.name(), e.getMessage());
            return null;
        }
    }

    private DeviceEntity mapEquipmentUnsafe(OpenHabItemDto equipment, Instant now) {
        List<String> tags = equipment.tags() != null ? equipment.tags() : List.of();
        List<OpenHabItemDto> members = equipment.members() != null ? equipment.members() : List.of();

        String deviceId = equipment.name();
        String label = equipment.label() != null ? equipment.label() : equipment.name();
        boolean available = isAvailable(members);

        DeviceClass deviceClass = resolveDeviceClass(tags);
        if (deviceClass == null) {
            return null;
        }

        return switch (deviceClass) {
            case THERMOSTAT -> mapThermostat(deviceId, label, available, now, members, tags);
            case LIGHT -> mapLight(deviceId, label, available, now, members);
            case SWITCH -> mapSwitch(deviceId, label, available, now, members);
            case LOCK -> mapLock(deviceId, label, available, now, members);
            case COVER -> mapCover(deviceId, label, available, now, members, tags);
            case MEDIA_PLAYER -> mapMediaPlayer(deviceId, label, available, now, members);
            case FAN -> mapFan(deviceId, label, available, now, members);
            case SENSOR -> mapSensor(deviceId, label, available, now, members, tags);
            default -> {
                LOG.warnf("Unhandled device class %s for equipment %s", deviceClass, deviceId);
                yield null;
            }
        };
    }

    // ---- device class resolution ----

    private DeviceClass resolveDeviceClass(List<String> tags) {
        for (String tag : tags) {
            if ("Equipment".equals(tag)) continue;

            if (HVAC_TAGS.contains(tag)) return DeviceClass.THERMOSTAT;
            if (LIGHT_TAGS.contains(tag)) return DeviceClass.LIGHT;
            if (SWITCH_TAGS.contains(tag)) return DeviceClass.SWITCH;
            if ("Lock".equals(tag)) return DeviceClass.LOCK;
            if (COVER_TAGS.contains(tag)) return DeviceClass.COVER;
            if (MEDIA_TAGS.contains(tag)) return DeviceClass.MEDIA_PLAYER;
            if ("Fan".equals(tag)) return DeviceClass.FAN;
            if ("Sensor".equals(tag)) return DeviceClass.SENSOR;
            if ("MotionDetector".equals(tag)) return DeviceClass.SENSOR;
            if ("Battery".equals(tag)) return DeviceClass.SENSOR;
            if ("SmokeDetector".equals(tag)) return DeviceClass.SENSOR;
        }

        LOG.warnf("No recognised Equipment tag in %s — skipping", tags);
        return null;
    }

    // ---- domain mappers ----

    private DeviceEntity mapThermostat(String deviceId, String label, boolean available,
                                       Instant now, List<OpenHabItemDto> members, List<String> equipmentTags) {
        Temperature currentTemp = null;
        Temperature targetTemp = null;
        BigDecimal heatingDemand = null;
        BigDecimal coolingDemand = null;
        boolean hasHeatingOrCoolingDemand = false;

        for (OpenHabItemDto m : members) {
            Set<String> mtags = tagSet(m);

            if (mtags.contains("Measurement") && mtags.contains("Temperature")) {
                currentTemp = parseTemperature(m);
            } else if (mtags.contains("Setpoint") && mtags.contains("Temperature")) {
                targetTemp = parseTemperature(m);
            } else if (mtags.contains("Measurement") && mtags.contains("Level")) {
                String nameLower = (m.name() + " " + nullSafe(m.label())).toLowerCase(Locale.ROOT);
                if (nameLower.contains("heating")) {
                    heatingDemand = parseOrNull(m.state());
                    hasHeatingOrCoolingDemand = true;
                } else if (nameLower.contains("cooling")) {
                    coolingDemand = parseOrNull(m.state());
                    hasHeatingOrCoolingDemand = true;
                }
            }
        }

        if (currentTemp == null) {
            currentTemp = new Temperature(BigDecimal.ZERO, Temperature.TemperatureUnit.CELSIUS);
        }
        if (targetTemp == null) {
            targetTemp = new Temperature(BigDecimal.ZERO, Temperature.TemperatureUnit.CELSIUS);
        }

        ThermostatMode mode = resolveHvacMode(members);

        if (hasHeatingOrCoolingDemand) {
            return OpenHabThermostat.builder()
                    .deviceId(deviceId).deviceClass(DeviceClass.THERMOSTAT).label(label)
                    .available(available).lastUpdated(now).tenancyId(tenancyId)
                    .currentTemperature(currentTemp).targetTemperature(targetTemp).mode(mode)
                    .heatingDemand(heatingDemand).coolingDemand(coolingDemand)
                    .build();
        }

        return new ThermostatDevice.Builder()
                .deviceId(deviceId).deviceClass(DeviceClass.THERMOSTAT).label(label)
                .available(available).lastUpdated(now).tenancyId(tenancyId)
                .currentTemperature(currentTemp).targetTemperature(targetTemp).mode(mode)
                .build();
    }

    private DeviceEntity mapLight(String deviceId, String label, boolean available,
                                   Instant now, List<OpenHabItemDto> members) {
        boolean on = false;
        OpenHabHsbType hsb = null;
        boolean hasColorItem = false;

        for (OpenHabItemDto m : members) {
            Set<String> mtags = tagSet(m);

            if (m.type() != null && m.type().contains("Color")) {
                hasColorItem = true;
                hsb = parseHsb(m.state());
                // Color item: brightness > 0 means on
                on = hsb != null && hsb.brightness().compareTo(BigDecimal.ZERO) > 0;
            } else if (mtags.contains("Control") && mtags.contains("Switch")) {
                on = "ON".equals(m.state());
            }
        }

        if (hasColorItem) {
            return OpenHabLight.builder()
                    .deviceId(deviceId).deviceClass(DeviceClass.LIGHT).label(label)
                    .available(available).lastUpdated(now).tenancyId(tenancyId)
                    .on(on).hsb(hsb)
                    .build();
        }

        return new LightDevice.Builder()
                .deviceId(deviceId).deviceClass(DeviceClass.LIGHT).label(label)
                .available(available).lastUpdated(now).tenancyId(tenancyId)
                .on(on)
                .build();
    }

    private SwitchDevice mapSwitch(String deviceId, String label, boolean available,
                                    Instant now, List<OpenHabItemDto> members) {
        boolean on = false;
        for (OpenHabItemDto m : members) {
            Set<String> mtags = tagSet(m);
            if (mtags.contains("Control") && mtags.contains("Switch")) {
                on = "ON".equals(m.state());
            }
        }
        return SwitchDevice.builder()
                .deviceId(deviceId).deviceClass(DeviceClass.SWITCH).label(label)
                .available(available).lastUpdated(now).tenancyId(tenancyId)
                .on(on)
                .build();
    }

    private LockDevice mapLock(String deviceId, String label, boolean available,
                                Instant now, List<OpenHabItemDto> members) {
        boolean locked = false;
        for (OpenHabItemDto m : members) {
            Set<String> mtags = tagSet(m);
            if (mtags.contains("Control") && mtags.contains("Switch")) {
                locked = "ON".equals(m.state());
            }
        }
        return new LockDevice.Builder()
                .deviceId(deviceId).deviceClass(DeviceClass.LOCK).label(label)
                .available(available).lastUpdated(now).tenancyId(tenancyId)
                .locked(locked)
                .build();
    }

    private DeviceEntity mapCover(String deviceId, String label, boolean available,
                                   Instant now, List<OpenHabItemDto> members, List<String> equipmentTags) {
        Integer position = null;

        for (OpenHabItemDto m : members) {
            Set<String> mtags = tagSet(m);
            if (mtags.contains("Status") && mtags.contains("OpenState")) {
                position = parseCoverPosition(m);
            }
        }

        boolean isRollershutterEquipment = equipmentTags.contains("Rollershutter") || equipmentTags.contains("Blinds");

        if (isRollershutterEquipment) {
            return OpenHabRollershutter.builder()
                    .deviceId(deviceId).deviceClass(DeviceClass.COVER).label(label)
                    .available(available).lastUpdated(now).tenancyId(tenancyId)
                    .position(position).moving(false)
                    .build();
        }

        return new CoverDevice.Builder()
                .deviceId(deviceId).deviceClass(DeviceClass.COVER).label(label)
                .available(available).lastUpdated(now).tenancyId(tenancyId)
                .position(position).moving(false)
                .build();
    }

    private MediaPlayerDevice mapMediaPlayer(String deviceId, String label, boolean available,
                                              Instant now, List<OpenHabItemDto> members) {
        Integer volume = null;

        for (OpenHabItemDto m : members) {
            Set<String> mtags = tagSet(m);
            if (mtags.contains("Control") && mtags.contains("SoundVolume")) {
                volume = parseIntOrNull(m.state());
            }
        }

        return MediaPlayerDevice.builder()
                .deviceId(deviceId).deviceClass(DeviceClass.MEDIA_PLAYER).label(label)
                .available(available).lastUpdated(now).tenancyId(tenancyId)
                .volume(volume)
                .build();
    }

    private FanDevice mapFan(String deviceId, String label, boolean available,
                              Instant now, List<OpenHabItemDto> members) {
        boolean on = false;
        for (OpenHabItemDto m : members) {
            Set<String> mtags = tagSet(m);
            if (mtags.contains("Control") && mtags.contains("Switch")) {
                on = "ON".equals(m.state());
            }
        }
        return FanDevice.builder()
                .deviceId(deviceId).deviceClass(DeviceClass.FAN).label(label)
                .available(available).lastUpdated(now).tenancyId(tenancyId)
                .on(on)
                .build();
    }

    private DeviceEntity mapSensor(String deviceId, String label, boolean available,
                                    Instant now, List<OpenHabItemDto> members, List<String> equipmentTags) {
        SensorType sensorType = resolveSensorType(equipmentTags, members);
        String unit = null;

        // Battery equipment gets "%" unit
        if (equipmentTags.contains("Battery")) {
            unit = "%";
        }

        BigDecimal numericValue = null;
        for (OpenHabItemDto m : members) {
            Set<String> mtags = tagSet(m);
            if (mtags.contains("Measurement") && mtags.contains("Humidity")) {
                numericValue = parseOrNull(m.state());
                break;
            }
            if (mtags.contains("Measurement") && mtags.contains("Level")) {
                numericValue = parseOrNull(m.state());
                break;
            }
            if (mtags.contains("Measurement") && mtags.contains("Power")) {
                return PowerSensor.builder()
                        .deviceId(deviceId).deviceClass(DeviceClass.POWER_SENSOR).label(label)
                        .available(available).lastUpdated(now).tenancyId(tenancyId)
                        .power(parseOrNull(m.state()))
                        .build();
            }
            if (mtags.contains("Measurement") && mtags.contains("Energy")) {
                return PowerSensor.builder()
                        .deviceId(deviceId).deviceClass(DeviceClass.POWER_SENSOR).label(label)
                        .available(available).lastUpdated(now).tenancyId(tenancyId)
                        .energy(parseOrNull(m.state()))
                        .build();
            }
            if (mtags.contains("Measurement") && mtags.contains("Presence")) {
                return PresenceSensor.builder()
                        .deviceId(deviceId).deviceClass(DeviceClass.PRESENCE_SENSOR).label(label)
                        .available(available).lastUpdated(now).tenancyId(tenancyId)
                        .present("ON".equals(m.state()))
                        .lastSeen(now)
                        .build();
            }
        }

        return SensorDevice.builder()
                .deviceId(deviceId).deviceClass(DeviceClass.SENSOR).label(label)
                .available(available).lastUpdated(now).tenancyId(tenancyId)
                .sensorType(sensorType)
                .numericValue(numericValue)
                .unit(unit)
                .build();
    }

    // ---- HVAC mode resolution ----

    private ThermostatMode resolveHvacMode(List<OpenHabItemDto> members) {
        for (OpenHabItemDto m : members) {
            if ("String".equals(m.type())) {
                String combined = (nullSafe(m.name()) + " " + nullSafe(m.label())).toLowerCase(Locale.ROOT);
                if (combined.contains("mode")) {
                    return mapHvacModeString(m.state());
                }
            }
        }
        LOG.warn("No mode item found in HVAC equipment — defaulting to OFF");
        return ThermostatMode.OFF;
    }

    private ThermostatMode mapHvacModeString(String state) {
        if (state == null) return ThermostatMode.OFF;
        return switch (state.toLowerCase(Locale.ROOT)) {
            case "heat" -> ThermostatMode.HEAT;
            case "cool" -> ThermostatMode.COOL;
            case "heat_cool", "auto" -> ThermostatMode.AUTO;
            case "off" -> ThermostatMode.OFF;
            case "fan_only" -> ThermostatMode.FAN_ONLY;
            case "dry" -> ThermostatMode.DRY;
            default -> {
                LOG.warnf("Unknown HVAC mode '%s' — defaulting to OFF", state);
                yield ThermostatMode.OFF;
            }
        };
    }

    // ---- sensor type resolution ----

    private SensorType resolveSensorType(List<String> equipmentTags, List<OpenHabItemDto> members) {
        if (equipmentTags.contains("MotionDetector")) return SensorType.MOTION;
        if (equipmentTags.contains("Battery")) return SensorType.GENERIC;
        if (equipmentTags.contains("SmokeDetector")) return SensorType.GENERIC;

        // Infer from members for generic Sensor tag
        for (OpenHabItemDto m : members) {
            Set<String> mtags = tagSet(m);
            if (mtags.contains("Measurement") && mtags.contains("Humidity")) return SensorType.HUMIDITY;
            if (mtags.contains("Measurement") && mtags.contains("Temperature")) return SensorType.TEMPERATURE;
        }
        return SensorType.GENERIC;
    }

    // ---- temperature parsing ----

    private Temperature parseTemperature(OpenHabItemDto member) {
        BigDecimal value = parseOrNull(member.state());
        if (value == null) {
            value = BigDecimal.ZERO;
        }
        Temperature.TemperatureUnit unit = detectTemperatureUnit(member);
        return new Temperature(value, unit);
    }

    private Temperature.TemperatureUnit detectTemperatureUnit(OpenHabItemDto member) {
        if (member.stateDescription() != null && member.stateDescription().pattern() != null) {
            String pattern = member.stateDescription().pattern();
            if (pattern.contains("°F") || pattern.contains("℉")) {
                return Temperature.TemperatureUnit.FAHRENHEIT;
            }
        }
        return Temperature.TemperatureUnit.CELSIUS;
    }

    // ---- cover position parsing ----

    private Integer parseCoverPosition(OpenHabItemDto member) {
        String type = member.type();
        String state = member.state();

        if (isNullOrUndef(state)) return null;

        if ("Contact".equals(type)) {
            return "OPEN".equals(state) ? 100 : 0;
        }

        // Rollershutter or Dimmer — percentage, inverted
        Integer raw = parseIntOrNull(state);
        if (raw != null) {
            return 100 - raw;
        }
        return null;
    }

    // ---- HSB parsing ----

    private OpenHabHsbType parseHsb(String state) {
        if (state == null || !state.contains(",")) return null;
        String[] parts = state.split(",");
        if (parts.length != 3) return null;
        try {
            return new OpenHabHsbType(
                    new BigDecimal(parts[0].trim()),
                    new BigDecimal(parts[1].trim()),
                    new BigDecimal(parts[2].trim()));
        } catch (NumberFormatException e) {
            LOG.warnf("Failed to parse HSB state '%s'", state);
            return null;
        }
    }

    // ---- availability ----

    private boolean isAvailable(List<OpenHabItemDto> members) {
        for (OpenHabItemDto m : members) {
            if (isNullOrUndef(m.state())) {
                return false;
            }
        }
        return true;
    }

    // ---- utility methods ----

    private static Set<String> tagSet(OpenHabItemDto item) {
        return item.tags() != null ? new HashSet<>(item.tags()) : Set.of();
    }

    private static boolean isNullOrUndef(String state) {
        return "NULL".equals(state) || "UNDEF".equals(state);
    }

    private static BigDecimal parseOrNull(String s) {
        if (s == null || s.isBlank() || isNullOrUndef(s)) return null;
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer parseIntOrNull(String s) {
        if (s == null || s.isBlank() || isNullOrUndef(s)) return null;
        try {
            return (int) Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String nullSafe(String s) {
        return s != null ? s : "";
    }
}
