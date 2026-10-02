package io.casehub.iot.desiredstate;

import io.casehub.desiredstate.api.NodeType;
import io.casehub.iot.api.DeviceClass;
import java.util.Objects;

public record PhysicalDeviceSpec(
    String deviceId,
    DeviceClass deviceClass,
    String label
) implements IoTNodeSpec {
    public PhysicalDeviceSpec {
        Objects.requireNonNull(deviceId, "deviceId required");
        Objects.requireNonNull(deviceClass, "deviceClass required");
        Objects.requireNonNull(label, "label required");
    }

    public NodeType nodeType() { return NodeType.of("physical-device"); }
}
