package io.casehub.iot.desiredstate;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DriftContext;
import io.casehub.desiredstate.api.DriftDecision;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.iot.api.DeviceClass;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IoTDriftPolicyTest {

    private final DefaultDesiredStateGraphFactory graphFactory = new DefaultDesiredStateGraphFactory();

    private DriftContext contextFor(DesiredStateGraph graph) {
        return new DriftContext("test-tenant", graph, new ActualState(Map.of()));
    }

    @Test
    void defaultPolicyReturnsReconcileForDriftedNode() {
        var policy = IoTDriftPolicy.builder().build();

        var spec = new DeviceConfigSpec("light-1", DeviceClass.LIGHT,
                                        Map.of("isOn", false));
        var node  = new DesiredNode(NodeId.of("light-1"), spec, HumanGating.NONE);
        var graph = graphFactory.of(List.of(node), List.of());

        DriftDecision decision = policy.evaluate(
                NodeId.of("light-1"), NodeStatus.DRIFTED, node, contextFor(graph));

        assertThat(decision).isInstanceOf(DriftDecision.Reconcile.class);
    }

    @Test
    void configuredExemptionReturnsExemptForMatchingDeviceClass() {
        var exemption = new io.casehub.desiredstate.api.ExemptionSpec(
                new io.casehub.desiredstate.api.RevertCondition.OnDuration(Duration.ofMinutes(30)),
                Map.of());
        var policy = IoTDriftPolicy.builder()
                                   .exemptClass(DeviceClass.LIGHT, exemption)
                                   .build();

        var spec = new DeviceConfigSpec("light-1", DeviceClass.LIGHT,
                                        Map.of("isOn", false));
        var node  = new DesiredNode(NodeId.of("light-1"), spec, HumanGating.NONE);
        var graph = graphFactory.of(List.of(node), List.of());

        DriftDecision decision = policy.evaluate(
                NodeId.of("light-1"), NodeStatus.DRIFTED, node, contextFor(graph));

        assertThat(decision).isInstanceOf(DriftDecision.Exempt.class);
        var exempt = (DriftDecision.Exempt) decision;
        assertThat(exempt.spec().revertCondition())
                .isInstanceOf(io.casehub.desiredstate.api.RevertCondition.OnDuration.class);
    }

    @Test
    void hardConstraintDeviceClassAlwaysReconciles() {
        var exemption = new io.casehub.desiredstate.api.ExemptionSpec(
                new io.casehub.desiredstate.api.RevertCondition.OnDuration(Duration.ofMinutes(30)),
                Map.of());
        var policy = IoTDriftPolicy.builder()
                                   .exemptClass(DeviceClass.LOCK, exemption)
                                   .hardConstraint(DeviceClass.LOCK)
                                   .build();

        var spec = new DeviceConfigSpec("lock-front", DeviceClass.LOCK,
                                        Map.of("isLocked", true));
        var node  = new DesiredNode(NodeId.of("lock-front"), spec, HumanGating.NONE);
        var graph = graphFactory.of(List.of(node), List.of());

        DriftDecision decision = policy.evaluate(
                NodeId.of("lock-front"), NodeStatus.DRIFTED, node, contextFor(graph));

        assertThat(decision).isInstanceOf(DriftDecision.Reconcile.class);
    }

    @Test
    void perDeviceIdOverrideTakesPrecedenceOverClassRule() {
        var classExemption = new io.casehub.desiredstate.api.ExemptionSpec(
                new io.casehub.desiredstate.api.RevertCondition.OnDuration(Duration.ofMinutes(30)),
                Map.of());
        var deviceExemption = new io.casehub.desiredstate.api.ExemptionSpec(
                new io.casehub.desiredstate.api.RevertCondition.Never(),
                Map.of("reason", "porch light stays on"));
        var policy = IoTDriftPolicy.builder()
                                   .exemptClass(DeviceClass.LIGHT, classExemption)
                                   .exemptDevice("light-porch", deviceExemption)
                                   .build();

        var spec = new DeviceConfigSpec("light-porch", DeviceClass.LIGHT,
                                        Map.of("isOn", false));
        var node  = new DesiredNode(NodeId.of("light-porch"), spec, HumanGating.NONE);
        var graph = graphFactory.of(List.of(node), List.of());

        DriftDecision decision = policy.evaluate(
                NodeId.of("light-porch"), NodeStatus.DRIFTED, node, contextFor(graph));

        assertThat(decision).isInstanceOf(DriftDecision.Exempt.class);
        var exempt = (DriftDecision.Exempt) decision;
        assertThat(exempt.spec().revertCondition())
                .isInstanceOf(io.casehub.desiredstate.api.RevertCondition.Never.class);
        assertThat(exempt.spec().metadata()).containsEntry("reason", "porch light stays on");
    }

    @Test
    void nonDriftedStatusAlwaysReturnsReconcile() {
        var exemption = new io.casehub.desiredstate.api.ExemptionSpec(
                new io.casehub.desiredstate.api.RevertCondition.OnDuration(Duration.ofMinutes(30)),
                Map.of());
        var policy = IoTDriftPolicy.builder()
                                   .exemptClass(DeviceClass.LIGHT, exemption)
                                   .build();

        var spec = new DeviceConfigSpec("light-1", DeviceClass.LIGHT,
                                        Map.of("isOn", false));
        var node  = new DesiredNode(NodeId.of("light-1"), spec, HumanGating.NONE);
        var graph = graphFactory.of(List.of(node), List.of());

        for (NodeStatus status : List.of(NodeStatus.ABSENT, NodeStatus.UNKNOWN, NodeStatus.PRESENT)) {
            DriftDecision decision = policy.evaluate(
                    NodeId.of("light-1"), status, node, contextFor(graph));
            assertThat(decision)
                    .as("status " + status + " should reconcile")
                    .isInstanceOf(DriftDecision.Reconcile.class);
        }
    }


    @Test
    void defaultPolicyExemptsLightsAndReconcileLocks() {
        var policy = new IoTDriftPolicy();

        var lightSpec = new DeviceConfigSpec("light-1", DeviceClass.LIGHT, Map.of("isOn", false));
        var lightNode = new DesiredNode(NodeId.of("light-1"), lightSpec, HumanGating.NONE);

        var lockSpec = new DeviceConfigSpec("lock-1", DeviceClass.LOCK, Map.of("isLocked", true));
        var lockNode = new DesiredNode(NodeId.of("lock-1"), lockSpec, HumanGating.NONE);

        var graph = graphFactory.of(List.of(lightNode, lockNode), List.of());

        assertThat(policy.evaluate(NodeId.of("light-1"), NodeStatus.DRIFTED, lightNode, contextFor(graph)))
                .isInstanceOf(DriftDecision.Exempt.class);
        assertThat(policy.evaluate(NodeId.of("lock-1"), NodeStatus.DRIFTED, lockNode, contextFor(graph)))
                .isInstanceOf(DriftDecision.Reconcile.class);
    }
}
