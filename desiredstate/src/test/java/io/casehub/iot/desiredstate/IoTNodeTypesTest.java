package io.casehub.iot.desiredstate;

import io.casehub.desiredstate.api.NodeType;
import io.casehub.iot.api.DeviceClass;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class IoTNodeTypesTest {

    @Test
    void configType_returnsCompositeNodeType() {
        assertThat(IoTNodeTypes.configType(DeviceClass.LIGHT))
            .isEqualTo(NodeType.of("device-config/light"));
    }

    @Test
    void physicalType_returnsCompositeNodeType() {
        assertThat(IoTNodeTypes.physicalType(DeviceClass.LOCK))
            .isEqualTo(NodeType.of("physical-device/lock"));
    }

    @Test
    void allConfig_containsAllDeviceClasses() {
        Set<NodeType> all = IoTNodeTypes.allConfig();
        assertThat(all).hasSize(DeviceClass.values().length);
        assertThat(all).contains(NodeType.of("device-config/light"));
        assertThat(all).contains(NodeType.of("device-config/thermostat"));
    }

    @Test
    void allPhysical_containsAllDeviceClasses() {
        Set<NodeType> all = IoTNodeTypes.allPhysical();
        assertThat(all).hasSize(DeviceClass.values().length);
        assertThat(all).contains(NodeType.of("physical-device/switch"));
    }

    @Test
    void all_containsBothPrefixesPlusReview() {
        Set<NodeType> all = IoTNodeTypes.all();
        assertThat(all).hasSize(DeviceClass.values().length * 2 + 1);
        assertThat(all).contains(IoTNodeTypes.IOT_REVIEW);
    }

    @Test
    void extractDeviceClass_fromConfigType() {
        assertThat(IoTNodeTypes.extractDeviceClass(NodeType.of("device-config/lock")))
            .isEqualTo("LOCK");
    }

    @Test
    void extractDeviceClass_fromPhysicalType() {
        assertThat(IoTNodeTypes.extractDeviceClass(NodeType.of("physical-device/thermostat")))
            .isEqualTo("THERMOSTAT");
    }
}
