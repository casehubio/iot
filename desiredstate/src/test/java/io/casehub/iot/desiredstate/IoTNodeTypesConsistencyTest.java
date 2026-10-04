package io.casehub.iot.desiredstate;

import io.casehub.desiredstate.api.NodeType;
import io.casehub.iot.api.DeviceClass;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class IoTNodeTypesConsistencyTest {

    @Test
    void provisioner_handlesAllDeviceClassVariants() {
        var provisioner = new IoTNodeProvisioner(null, java.util.List.of());
        Set<NodeType> handled = provisioner.handledTypes();
        for (DeviceClass dc : DeviceClass.values()) {
            assertThat(handled).contains(IoTNodeTypes.configType(dc));
            assertThat(handled).contains(IoTNodeTypes.physicalType(dc));
        }
        assertThat(handled).contains(IoTNodeTypes.IOT_REVIEW);
    }

    @Test
    void actualStateAdapter_handlesAllDeviceClassVariants() {
        var adapter = new IoTActualStateAdapter(null);
        Set<NodeType> handled = adapter.handledTypes();
        for (DeviceClass dc : DeviceClass.values()) {
            assertThat(handled).contains(IoTNodeTypes.configType(dc));
            assertThat(handled).contains(IoTNodeTypes.physicalType(dc));
        }
    }

    @Test
    void specTypes_matchNodeTypesUtility() {
        for (DeviceClass dc : DeviceClass.values()) {
            var configSpec = new DeviceConfigSpec("test", dc, Map.of());
            assertThat(configSpec.nodeType()).isEqualTo(IoTNodeTypes.configType(dc));

            var physicalSpec = new PhysicalDeviceSpec("test", dc, "Test");
            assertThat(physicalSpec.nodeType()).isEqualTo(IoTNodeTypes.physicalType(dc));
        }
    }
}
