package io.casehub.iot.openhab;

import io.casehub.iot.api.*;
import io.casehub.iot.openhab.internal.OpenHabItemDto;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
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
 *
 * <p>Resolution produces a {@link ResolvedDeviceFields} instance; construction is then
 * delegated to {@link OpenHabDeviceBuilder#build(ResolvedDeviceFields)}. This separation
 * allows Thing-based resolution (Task 4) to reuse the same builder.</p>
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

    @Inject
    @ConfigProperty(name = "casehub.iot.tenancy-id")
    String tenancyId;

    @Inject
    public OpenHabEntityMapper() {
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
        ResolvedDeviceFields fields = resolveFromEquipment(equipment, now);
        if (fields == null) {
            return null;
        }
        return OpenHabDeviceBuilder.build(fields);
    }

    // ---- Equipment resolution ----

    /**
     * Resolves device fields from an OpenHAB Equipment Group by iterating its members
     * and extracting values based on semantic tags.
     *
     * @param equipment the Equipment Group DTO with inlined members
     * @param now       timestamp to use as lastUpdated
     * @return resolved fields, or null if the Equipment tag is unrecognised
     */
    ResolvedDeviceFields resolveFromEquipment(OpenHabItemDto equipment, Instant now) {
        List<String> tags = equipment.tags() != null ? equipment.tags() : List.of();
        List<OpenHabItemDto> members = equipment.members() != null ? equipment.members() : List.of();

        String deviceId = equipment.name();
        String label = equipment.label() != null ? equipment.label() : equipment.name();
        boolean available = isAvailable(members);

        DeviceClass deviceClass = resolveDeviceClass(tags);
        if (deviceClass == null) {
            return null;
        }

        ResolvedDeviceFields.Builder b = ResolvedDeviceFields.builder()
                .deviceId(deviceId)
                .label(label)
                .available(available)
                .now(now)
                .tenancyId(tenancyId)
                .deviceClass(deviceClass);

        switch (deviceClass) {
            case THERMOSTAT -> resolveThermostat(b, members, tags);
            case LIGHT -> resolveLight(b, members);
            case SWITCH -> resolveSwitch(b, members);
            case LOCK -> resolveLock(b, members);
            case COVER -> resolveCover(b, members, tags);
            case MEDIA_PLAYER -> resolveMediaPlayer(b, members);
            case FAN -> resolveFan(b, members);
            case SENSOR -> resolveSensor(b, members, tags);
            case CAMERA -> {} // no standard OH channels map to streaming state
            default -> {
                LOG.warnf("Unhandled device class %s for equipment %s", deviceClass, deviceId);
                return null;
            }
        }

        return b.build();
    }

    // ---- field resolution by device class ----

    private void resolveThermostat(ResolvedDeviceFields.Builder b,
                                   List<OpenHabItemDto> members, List<String> equipmentTags) {
        Temperature currentTemp = null;
        Temperature targetTemp = null;
        BigDecimal heatingDemand = null;
        BigDecimal coolingDemand = null;
        boolean hasHeatingOrCoolingDemand = false;

        for (OpenHabItemDto m : members) {
            Set<String> mtags = m.tagSet();

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

        b.currentTemperature(currentTemp)
         .targetTemperature(targetTemp)
         .mode(resolveHvacMode(members));

        if (hasHeatingOrCoolingDemand) {
            b.heatingDemand(heatingDemand).coolingDemand(coolingDemand);
        }
    }

    private void resolveLight(ResolvedDeviceFields.Builder b, List<OpenHabItemDto> members) {
        boolean on = false;
        OpenHabHsbType hsb = null;

        for (OpenHabItemDto m : members) {
            Set<String> mtags = m.tagSet();

            if (m.type() != null && m.type().contains("Color")) {
                hsb = parseHsb(m.state());
                // Color item: brightness > 0 means on
                on = hsb != null && hsb.brightness().compareTo(BigDecimal.ZERO) > 0;
            } else if (mtags.contains("Control") && mtags.contains("Switch")) {
                on = "ON".equals(m.state());
            }
        }

        b.on(on);
        if (hsb != null) {
            b.hsb(hsb).brightness(hsb.brightness().intValue());
        }
    }

    private void resolveSwitch(ResolvedDeviceFields.Builder b, List<OpenHabItemDto> members) {
        boolean on = false;
        for (OpenHabItemDto m : members) {
            Set<String> mtags = m.tagSet();
            if (mtags.contains("Control") && mtags.contains("Switch")) {
                on = "ON".equals(m.state());
            }
        }
        b.on(on);
    }

    private void resolveLock(ResolvedDeviceFields.Builder b, List<OpenHabItemDto> members) {
        boolean locked = false;
        for (OpenHabItemDto m : members) {
            Set<String> mtags = m.tagSet();
            if (mtags.contains("Control") && mtags.contains("Switch")) {
                locked = "ON".equals(m.state());
            }
        }
        b.locked(locked);
    }

    private void resolveCover(ResolvedDeviceFields.Builder b,
                              List<OpenHabItemDto> members, List<String> equipmentTags) {
        Integer position = null;
        for (OpenHabItemDto m : members) {
            Set<String> mtags = m.tagSet();
            if (mtags.contains("Status") && mtags.contains("OpenState")) {
                position = parseCoverPosition(m);
            }
        }
        boolean isRollershutterEquipment = equipmentTags.contains("Rollershutter") || equipmentTags.contains("Blinds");
        b.position(position).isRollershutter(isRollershutterEquipment);
    }

    private void resolveMediaPlayer(ResolvedDeviceFields.Builder b, List<OpenHabItemDto> members) {
        Integer volume = null;
        for (OpenHabItemDto m : members) {
            Set<String> mtags = m.tagSet();
            if (mtags.contains("Control") && mtags.contains("SoundVolume")) {
                volume = parseIntOrNull(m.state());
            }
        }
        b.volume(volume);
    }

    private void resolveFan(ResolvedDeviceFields.Builder b, List<OpenHabItemDto> members) {
        boolean on = false;
        for (OpenHabItemDto m : members) {
            Set<String> mtags = m.tagSet();
            if (mtags.contains("Control") && mtags.contains("Switch")) {
                on = "ON".equals(m.state());
            }
        }
        b.on(on);
    }

    private void resolveSensor(ResolvedDeviceFields.Builder b,
                               List<OpenHabItemDto> members, List<String> equipmentTags) {
        SensorType sensorType = resolveSensorType(equipmentTags, members);

        // Battery equipment gets "%" unit
        String unit = null;
        if (equipmentTags.contains("Battery")) {
            unit = "%";
        }

        BigDecimal numericValue = null;
        for (OpenHabItemDto m : members) {
            Set<String> mtags = m.tagSet();
            if (mtags.contains("Measurement") && mtags.contains("Humidity")) {
                numericValue = parseOrNull(m.state());
                break;
            }
            if (mtags.contains("Measurement") && mtags.contains("Level")) {
                numericValue = parseOrNull(m.state());
                break;
            }
            if (mtags.contains("Measurement") && mtags.contains("Power")) {
                b.deviceClass(DeviceClass.POWER_SENSOR)
                 .power(parseOrNull(m.state()));
                return;
            }
            if (mtags.contains("Measurement") && mtags.contains("Energy")) {
                b.deviceClass(DeviceClass.POWER_SENSOR)
                 .energy(parseOrNull(m.state()));
                return;
            }
            if (mtags.contains("Measurement") && mtags.contains("Presence")) {
                b.deviceClass(DeviceClass.PRESENCE_SENSOR)
                 .present("ON".equals(m.state()));
                return;
            }
        }

        b.sensorType(sensorType).numericValue(numericValue).unit(unit);
    }

    // ---- device class resolution ----

    DeviceClass resolveDeviceClass(List<String> tags) {
        for (String tag : tags) {
            if ("Equipment".equals(tag)) continue;

            if (HVAC_TAGS.contains(tag)) return DeviceClass.THERMOSTAT;
            if (LIGHT_TAGS.contains(tag)) return DeviceClass.LIGHT;
            if (SWITCH_TAGS.contains(tag)) return DeviceClass.SWITCH;
            if ("Lock".equals(tag)) return DeviceClass.LOCK;
            if (COVER_TAGS.contains(tag)) return DeviceClass.COVER;
            if (MEDIA_TAGS.contains(tag)) return DeviceClass.MEDIA_PLAYER;
            if ("Fan".equals(tag)) return DeviceClass.FAN;
            if ("Camera".equals(tag)) return DeviceClass.CAMERA;
            if ("Sensor".equals(tag)) return DeviceClass.SENSOR;
            if ("MotionDetector".equals(tag)) return DeviceClass.SENSOR;
            if ("Battery".equals(tag)) return DeviceClass.SENSOR;
            if ("SmokeDetector".equals(tag)) return DeviceClass.SENSOR;
        }

        LOG.warnf("No recognised Equipment tag in %s — skipping", tags);
        return null;
    }

    // ---- HVAC mode resolution ----

    static ThermostatMode resolveHvacMode(List<OpenHabItemDto> members) {
        for (OpenHabItemDto m : members) {
            Set<String> mtags = m.tagSet();
            if (mtags.contains("Control") && mtags.contains("Switch")) {
                if (!"ON".equals(m.state())) {
                    return ThermostatMode.OFF;
                }
            }
        }
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

    static ThermostatMode mapHvacModeString(String state) {
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

    static SensorType resolveSensorType(List<String> equipmentTags, List<OpenHabItemDto> members) {
        if (equipmentTags.contains("MotionDetector")) return SensorType.MOTION;
        if (equipmentTags.contains("Battery")) return SensorType.GENERIC;
        if (equipmentTags.contains("SmokeDetector")) return SensorType.GENERIC;

        // Infer from members for generic Sensor tag
        for (OpenHabItemDto m : members) {
            Set<String> mtags = m.tagSet();
            if (mtags.contains("Measurement") && mtags.contains("Humidity")) return SensorType.HUMIDITY;
            if (mtags.contains("Measurement") && mtags.contains("Temperature")) return SensorType.TEMPERATURE;
        }
        return SensorType.GENERIC;
    }

    // ---- temperature parsing ----

    static Temperature parseTemperature(OpenHabItemDto member) {
        BigDecimal value = parseOrNull(member.state());
        if (value == null) {
            value = BigDecimal.ZERO;
        }
        Temperature.TemperatureUnit unit = detectTemperatureUnit(member);
        return new Temperature(value, unit);
    }

    static Temperature.TemperatureUnit detectTemperatureUnit(OpenHabItemDto member) {
        if (member.stateDescription() != null && member.stateDescription().pattern() != null) {
            String pattern = member.stateDescription().pattern();
            if (pattern.contains("°F") || pattern.contains("℉")) {
                return Temperature.TemperatureUnit.FAHRENHEIT;
            }
        }
        return Temperature.TemperatureUnit.CELSIUS;
    }

    // ---- cover position parsing ----

    static Integer parseCoverPosition(OpenHabItemDto member) {
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

    static OpenHabHsbType parseHsb(String state) {
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

    static boolean isAvailable(List<OpenHabItemDto> members) {
        if (members.isEmpty()) return false;
        for (OpenHabItemDto m : members) {
            if (!isNullOrUndef(m.state())) {
                return true;
            }
        }
        return false;
    }

    // ---- utility methods ----

    static boolean isNullOrUndef(String state) {
        return "NULL".equals(state) || "UNDEF".equals(state);
    }

    static BigDecimal parseOrNull(String s) {
        if (s == null || s.isBlank() || isNullOrUndef(s)) return null;
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Integer parseIntOrNull(String s) {
        if (s == null || s.isBlank() || isNullOrUndef(s)) return null;
        try {
            return (int) Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String nullSafe(String s) {
        return s != null ? s : "";
    }
}
