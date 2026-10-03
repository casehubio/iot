package io.casehub.iot.desiredstate;

import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DriftContext;
import io.casehub.desiredstate.api.DriftDecision;
import io.casehub.desiredstate.api.DriftPolicy;
import io.casehub.desiredstate.api.ExemptionSpec;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.iot.api.DeviceClass;

import io.casehub.desiredstate.api.RevertCondition;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class IoTDriftPolicy implements DriftPolicy {

    private static final ExemptionSpec DEFAULT_OVERRIDE_EXEMPTION =
            new ExemptionSpec(new RevertCondition.OnDuration(Duration.ofMinutes(30)), Map.of());

    private static final Set<DeviceClass> DEFAULT_HARD_CONSTRAINTS =
            Set.of(DeviceClass.LOCK, DeviceClass.CAMERA);

    private static final Map<DeviceClass, ExemptionSpec> DEFAULT_CLASS_RULES = Map.of(
            DeviceClass.LIGHT, DEFAULT_OVERRIDE_EXEMPTION,
            DeviceClass.THERMOSTAT, DEFAULT_OVERRIDE_EXEMPTION,
            DeviceClass.FAN, DEFAULT_OVERRIDE_EXEMPTION,
            DeviceClass.COVER, DEFAULT_OVERRIDE_EXEMPTION,
            DeviceClass.MEDIA_PLAYER, DEFAULT_OVERRIDE_EXEMPTION
    );

    private final Map<DeviceClass, ExemptionSpec> classRules;
    private final Map<String, ExemptionSpec> deviceRules;
    private final Set<DeviceClass> hardConstraints;

    IoTDriftPolicy() {
        this(DEFAULT_CLASS_RULES, Map.of(), DEFAULT_HARD_CONSTRAINTS);
    }

    IoTDriftPolicy(Map<DeviceClass, ExemptionSpec> classRules, Map<String, ExemptionSpec> deviceRules, Set<DeviceClass> hardConstraints) {
        this.classRules = Map.copyOf(classRules);
        this.deviceRules = Map.copyOf(deviceRules);
        this.hardConstraints = Set.copyOf(hardConstraints);
    }

    @Override
    public DriftDecision evaluate(NodeId nodeId, NodeStatus status,
                                  DesiredNode node, DriftContext context) {
        if (status != NodeStatus.DRIFTED) {
            return DriftDecision.reconcile();
        }
        if (node.spec() instanceof IoTNodeSpec iotSpec) {
            if (hardConstraints.contains(iotSpec.deviceClass())) {
                return DriftDecision.reconcile();
            }
            ExemptionSpec deviceRule = deviceRules.get(iotSpec.deviceId());
            if (deviceRule != null) {
                return DriftDecision.exempt(deviceRule);
            }
            ExemptionSpec classRule = classRules.get(iotSpec.deviceClass());
            if (classRule != null) {
                return DriftDecision.exempt(classRule);
            }
        }
        return DriftDecision.reconcile();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final java.util.HashMap<DeviceClass, ExemptionSpec> classRules = new java.util.HashMap<>();
        private final java.util.HashMap<String, ExemptionSpec> deviceRules = new java.util.HashMap<>();
        private final java.util.HashSet<DeviceClass> hardConstraints = new java.util.HashSet<>();

        public Builder exemptClass(DeviceClass deviceClass, ExemptionSpec spec) {
            classRules.put(deviceClass, spec);
            return this;
        }

        public Builder exemptDevice(String deviceId, ExemptionSpec spec) {
            deviceRules.put(deviceId, spec);
            return this;
        }

        public Builder hardConstraint(DeviceClass deviceClass) {
            hardConstraints.add(deviceClass);
            return this;
        }

        public IoTDriftPolicy build() {
            return new IoTDriftPolicy(classRules, deviceRules, hardConstraints);
        }
    }
}
