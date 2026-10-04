package io.casehub.iot.desiredstate;

import io.casehub.desiredstate.api.NodeType;
import io.casehub.iot.api.DeviceClass;

import java.util.HashSet;
import java.util.Set;

public final class IoTNodeTypes {

    public static final NodeType IOT_REVIEW = NodeType.of("iot-review");

    private static final Set<NodeType> ALL_CONFIG;
    private static final Set<NodeType> ALL_PHYSICAL;
    private static final Set<NodeType> ALL;

    static {
        var config = new HashSet<NodeType>();
        var physical = new HashSet<NodeType>();
        for (DeviceClass dc : DeviceClass.values()) {
            config.add(configType(dc));
            physical.add(physicalType(dc));
        }
        ALL_CONFIG = Set.copyOf(config);
        ALL_PHYSICAL = Set.copyOf(physical);
        var all = new HashSet<NodeType>();
        all.addAll(ALL_CONFIG);
        all.addAll(ALL_PHYSICAL);
        all.add(IOT_REVIEW);
        ALL = Set.copyOf(all);
    }

    public static NodeType configType(DeviceClass dc) {
        return NodeType.of("device-config/" + dc.name().toLowerCase());
    }

    public static NodeType physicalType(DeviceClass dc) {
        return NodeType.of("physical-device/" + dc.name().toLowerCase());
    }

    public static Set<NodeType> allConfig() { return ALL_CONFIG; }
    public static Set<NodeType> allPhysical() { return ALL_PHYSICAL; }
    public static Set<NodeType> all() { return ALL; }

    public static String extractDeviceClass(NodeType type) {
        String v = type.value();
        int slash = v.indexOf('/');
        if (slash < 0) throw new IllegalArgumentException("Not a composite NodeType: " + v);
        return v.substring(slash + 1).toUpperCase();
    }

    private IoTNodeTypes() {}
}
