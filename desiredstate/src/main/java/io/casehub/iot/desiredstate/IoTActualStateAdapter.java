package io.casehub.iot.desiredstate;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.ActualStateAdapter;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.iot.api.spi.DeviceRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class IoTActualStateAdapter implements ActualStateAdapter {

    private final DeviceRegistry registry;

    @Inject
    public IoTActualStateAdapter(DeviceRegistry registry) {
        this.registry = registry;
    }

    private static final Set<NodeType> HANDLED_TYPES;
    static {
        var types = new java.util.HashSet<>(IoTNodeTypes.allConfig());
        types.addAll(IoTNodeTypes.allPhysical());
        HANDLED_TYPES = Set.copyOf(types);
    }

    public Set<NodeType> handledTypes() {
        return HANDLED_TYPES;
    }

    @Override
    public ActualState readActual(DesiredStateGraph desired, String tenancyId) {
        Map<NodeId, NodeStatus> statuses = new HashMap<>();
        for (var entry : desired.nodes().entrySet()) {
            DesiredNode node = entry.getValue();
            if (node.spec() instanceof IoTNodeSpec spec) {
                statuses.put(entry.getKey(), checkNode(spec));
            }
        }
        return new ActualState(statuses);
    }

    private NodeStatus checkNode(IoTNodeSpec spec) {
        var device = registry.findById(spec.deviceId());
        if (device.isEmpty()) return NodeStatus.ABSENT;

        return switch (spec) {
            case PhysicalDeviceSpec s ->
                device.get().deviceClass() == s.deviceClass()
                    ? NodeStatus.PRESENT : NodeStatus.DRIFTED;
            case DeviceConfigSpec s -> {
                var actualNorm = CapabilityNormalizer.normalize(device.get().capabilities());
                var desiredNorm = CapabilityNormalizer.normalize(s.desiredCapabilities());
                boolean matches = desiredNorm.entrySet().stream()
                    .allMatch(e -> e.getValue().equals(actualNorm.get(e.getKey())));
                yield matches ? NodeStatus.PRESENT : NodeStatus.DRIFTED;
            }
        };
    }
}
