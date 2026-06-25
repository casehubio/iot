package io.casehub.iot.api;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.DatabindContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.jsontype.impl.TypeIdResolverBase;

import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Jackson type-id resolver for the {@link DeviceEntity} hierarchy.
 *
 * <p>Uses compound IDs of the form {@code "DeviceClass:SimpleClassName"}
 * (e.g. {@code "SWITCH:SwitchDevice"}, {@code "THERMOSTAT:HomeAssistantThermostat"}).
 *
 * <p>All 11 common types are registered in the static initializer.
 * Vendor modules register supplement types via {@link #registerType(String, Class)}.
 *
 * <p>Deserialization tries an exact match first. On miss, it splits on {@code :}
 * and falls back to the common type for that {@link DeviceClass} prefix — this
 * provides graceful degradation when a remote node sends a vendor supplement
 * type unknown to the receiver.
 */
public class DeviceTypeIdResolver extends TypeIdResolverBase {

    private static final Logger LOG = Logger.getLogger(DeviceTypeIdResolver.class);

    /** Compound-ID → concrete class. Thread-safe for runtime registration. */
    private static final ConcurrentHashMap<String, Class<? extends DeviceEntity>> REGISTRY =
            new ConcurrentHashMap<>();

    /** DeviceClass name → common type class. Used for fallback resolution. */
    private static final Map<String, Class<? extends DeviceEntity>> FALLBACK = Map.ofEntries(
            Map.entry("SWITCH", SwitchDevice.class),
            Map.entry("LIGHT", LightDevice.class),
            Map.entry("THERMOSTAT", ThermostatDevice.class),
            Map.entry("SENSOR", SensorDevice.class),
            Map.entry("PRESENCE_SENSOR", PresenceSensor.class),
            Map.entry("POWER_SENSOR", PowerSensor.class),
            Map.entry("LOCK", LockDevice.class),
            Map.entry("COVER", CoverDevice.class),
            Map.entry("MEDIA_PLAYER", MediaPlayerDevice.class),
            Map.entry("FAN", FanDevice.class),
            Map.entry("CAMERA", CameraDevice.class)
    );

    static {
        // Register all 11 common types with compound IDs
        REGISTRY.put("SWITCH:SwitchDevice", SwitchDevice.class);
        REGISTRY.put("LIGHT:LightDevice", LightDevice.class);
        REGISTRY.put("THERMOSTAT:ThermostatDevice", ThermostatDevice.class);
        REGISTRY.put("SENSOR:SensorDevice", SensorDevice.class);
        REGISTRY.put("PRESENCE_SENSOR:PresenceSensor", PresenceSensor.class);
        REGISTRY.put("POWER_SENSOR:PowerSensor", PowerSensor.class);
        REGISTRY.put("LOCK:LockDevice", LockDevice.class);
        REGISTRY.put("COVER:CoverDevice", CoverDevice.class);
        REGISTRY.put("MEDIA_PLAYER:MediaPlayerDevice", MediaPlayerDevice.class);
        REGISTRY.put("FAN:FanDevice", FanDevice.class);
        REGISTRY.put("CAMERA:CameraDevice", CameraDevice.class);
    }

    /**
     * Register a vendor supplement type for exact compound-ID resolution.
     *
     * @param compoundId format: {@code "DEVICE_CLASS:SimpleClassName"}
     * @param type       the concrete device class
     */
    public static void registerType(String compoundId, Class<? extends DeviceEntity> type) {
        REGISTRY.put(compoundId, type);
    }

    /**
     * Remove a previously registered type. Primarily for test cleanup.
     */
    public static void deregisterType(String compoundId) {
        REGISTRY.remove(compoundId);
    }

    /**
     * Check whether a compound ID is registered (exact match).
     */
    public static boolean isRegistered(String compoundId) {
        return REGISTRY.containsKey(compoundId);
    }

    @Override
    public String idFromValue(Object value) {
        DeviceEntity device = (DeviceEntity) value;
        return device.deviceClass().name() + ":" + value.getClass().getSimpleName();
    }

    @Override
    public String idFromValueAndType(Object value, Class<?> suggestedType) {
        return idFromValue(value);
    }

    @Override
    public JavaType typeFromId(DatabindContext context, String id) throws IOException {
        // 1. Exact match
        Class<? extends DeviceEntity> type = REGISTRY.get(id);
        if (type != null) {
            return context.constructType(type);
        }

        // 2. Fallback via DeviceClass prefix
        int colon = id.indexOf(':');
        if (colon > 0) {
            String prefix = id.substring(0, colon);
            Class<? extends DeviceEntity> fallback = FALLBACK.get(prefix);
            if (fallback != null) {
                LOG.warnf("Unknown device type ID '%s' — falling back to common type %s. "
                        + "Add the vendor module to classpath for full type fidelity.",
                        id, fallback.getSimpleName());
                return context.constructType(fallback);
            }
        }

        // 3. No resolution possible
        throw new IOException(
                "Cannot resolve device type ID '" + id + "': "
                        + "no exact match and no fallback for DeviceClass prefix");
    }

    @Override
    public JsonTypeInfo.Id getMechanism() {
        return JsonTypeInfo.Id.CUSTOM;
    }
}
