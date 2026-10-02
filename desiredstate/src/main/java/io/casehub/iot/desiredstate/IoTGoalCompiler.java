package io.casehub.iot.desiredstate;

import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.Dependency;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.GoalCompiler;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class IoTGoalCompiler implements GoalCompiler<IoTGoals> {

    @Override
    public CompilationResult compile(IoTGoals goals, DesiredStateGraphFactory factory) {
        Map<String, IoTDeviceGoal> lookup = new HashMap<>();
        for (IoTDeviceGoal goal : goals.devices()) {
            if (lookup.containsKey(goal.deviceId())) {
                throw new IllegalArgumentException("Duplicate deviceId: " + goal.deviceId());
            }
            lookup.put(goal.deviceId(), goal);
        }

        List<DesiredNode> nodes = new ArrayList<>();
        List<Dependency> deps = new ArrayList<>();

        for (IoTDeviceGoal goal : goals.devices()) {
            if (goal.physical()) {
                nodes.add(new DesiredNode(
                    NodeId.of(goal.deviceId()),
                    new PhysicalDeviceSpec(goal.deviceId(), goal.deviceClass(), goal.label()), HumanGating.ALL));
                nodes.add(new DesiredNode(
                    NodeId.of(goal.deviceId() + "-config"),
                    new DeviceConfigSpec(goal.deviceId(), goal.deviceClass(), goal.config()), HumanGating.NONE));
                deps.add(new Dependency(
                    NodeId.of(goal.deviceId() + "-config"), NodeId.of(goal.deviceId())));
            } else {
                nodes.add(new DesiredNode(
                    NodeId.of(goal.deviceId()),
                    new DeviceConfigSpec(goal.deviceId(), goal.deviceClass(), goal.config()), HumanGating.NONE));
            }

            for (String depId : goal.dependsOn()) {
                deps.add(new Dependency(NodeId.of(goal.deviceId()), NodeId.of(depId)));
            }
        }

        return CompilationResult.single(factory.of(nodes, deps));
    }
}
