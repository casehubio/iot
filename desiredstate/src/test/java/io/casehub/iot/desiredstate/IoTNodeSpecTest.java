package io.casehub.iot.desiredstate;

import io.casehub.desiredstate.api.NodeType;
import io.casehub.iot.api.DeviceClass;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IoTNodeSpecTest {

    @Test
    void deviceConfigSpec_nodeType() {
        var spec = new DeviceConfigSpec("d1", DeviceClass.SWITCH, Map.of("isOn", true));
        assertThat(spec.nodeType()).isEqualTo(IoTNodeTypes.configType(DeviceClass.SWITCH));
        assertThat(spec.deviceId()).isEqualTo("d1");
        assertThat(spec.deviceClass()).isEqualTo(DeviceClass.SWITCH);
    }

    @Test
    void physicalDeviceSpec_nodeType() {
        var spec = new PhysicalDeviceSpec("d1", DeviceClass.THERMOSTAT, "Label");
        assertThat(spec.nodeType()).isEqualTo(IoTNodeTypes.physicalType(DeviceClass.THERMOSTAT));
        assertThat(spec.deviceId()).isEqualTo("d1");
        assertThat(spec.deviceClass()).isEqualTo(DeviceClass.THERMOSTAT);
    }

    @Test
    void reviewSpec_nodeType() {
        var spec = new IoTReviewSpec(io.casehub.desiredstate.api.NodeId.of("n1"), "reason");
        assertThat(spec.nodeType()).isEqualTo(NodeType.of("iot-review"));
    }

    @Test
    void deviceConfigSpec_rejectsNullCapabilityValues() {
        var caps = new java.util.HashMap<String, Object>();
        caps.put("isOn", null);
        assertThatThrownBy(() -> new DeviceConfigSpec("d1", DeviceClass.SWITCH, caps))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deviceConfigSpec_rejectsNullDeviceId() {
        assertThatThrownBy(() -> new DeviceConfigSpec(null, DeviceClass.SWITCH, Map.of()))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void physicalDeviceSpec_rejectsNullDeviceId() {
        assertThatThrownBy(() -> new PhysicalDeviceSpec(null, DeviceClass.SWITCH, "Label"))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void ioTNodeSpec_sealedHierarchy() {
        IoTNodeSpec config = new DeviceConfigSpec("d1", DeviceClass.SWITCH, Map.of());
        IoTNodeSpec physical = new PhysicalDeviceSpec("d1", DeviceClass.SWITCH, "Label");
        assertThat(config).isInstanceOf(IoTNodeSpec.class);
        assertThat(physical).isInstanceOf(IoTNodeSpec.class);
    }
}
