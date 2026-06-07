package io.casehub.iot.api;

import java.util.Map;
import java.util.Objects;

public record DeviceCommand(
    String targetDeviceId,
    String action,
    Map<String, Object> parameters,
    String dispatchedBy,
    String correlationId
) {
    public DeviceCommand {
        Objects.requireNonNull(targetDeviceId, "targetDeviceId");
        Objects.requireNonNull(action, "action");
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }

    public static final String ACTION_TURN_ON = "turn_on";
    public static final String ACTION_TURN_OFF = "turn_off";
    public static final String ACTION_SET_TEMPERATURE = "set_temperature";
    public static final String ACTION_LOCK = "lock";
    public static final String ACTION_UNLOCK = "unlock";
    public static final String ACTION_SET_POSITION = "set_position";
    public static final String ACTION_SET_VOLUME = "set_volume";

    public static DeviceCommand turnOn(String targetDeviceId, Map<String, Object> parameters,
                                       String dispatchedBy, String correlationId) {
        return new DeviceCommand(targetDeviceId, ACTION_TURN_ON, parameters, dispatchedBy, correlationId);
    }

    public static DeviceCommand turnOff(String targetDeviceId,
                                        String dispatchedBy, String correlationId) {
        return new DeviceCommand(targetDeviceId, ACTION_TURN_OFF, Map.of(), dispatchedBy, correlationId);
    }

    public static DeviceCommand setTemperature(String targetDeviceId, Temperature target,
                                               String dispatchedBy, String correlationId) {
        return new DeviceCommand(targetDeviceId, ACTION_SET_TEMPERATURE,
            Map.of("temperature", target.value(), "unit", target.unit().name()),
            dispatchedBy, correlationId);
    }

    public static DeviceCommand lock(String targetDeviceId,
                                     String dispatchedBy, String correlationId) {
        return new DeviceCommand(targetDeviceId, ACTION_LOCK, Map.of(), dispatchedBy, correlationId);
    }

    public static DeviceCommand unlock(String targetDeviceId,
                                       String dispatchedBy, String correlationId) {
        return new DeviceCommand(targetDeviceId, ACTION_UNLOCK, Map.of(), dispatchedBy, correlationId);
    }

    public static DeviceCommand setPosition(String targetDeviceId, int position,
                                            String dispatchedBy, String correlationId) {
        return new DeviceCommand(targetDeviceId, ACTION_SET_POSITION,
            Map.of("position", position), dispatchedBy, correlationId);
    }

    public static DeviceCommand setVolume(String targetDeviceId, int volume,
                                          String dispatchedBy, String correlationId) {
        return new DeviceCommand(targetDeviceId, ACTION_SET_VOLUME,
            Map.of("volume", volume), dispatchedBy, correlationId);
    }
}
