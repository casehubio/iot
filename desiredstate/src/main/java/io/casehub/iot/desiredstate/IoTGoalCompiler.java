package io.casehub.iot.desiredstate;

import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.Dependency;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.GoalCompiler;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.OrderingConstraint;
import io.casehub.iot.api.DeviceClass;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

@ApplicationScoped
public class IoTGoalCompiler implements GoalCompiler<IoTGoals> {

    private final IoTOrderingLoader orderingLoader;

    @Inject
    public IoTGoalCompiler(IoTOrderingLoader orderingLoader) {
        this.orderingLoader = orderingLoader;
    }

    public IoTGoalCompiler() {
        this.orderingLoader = null;
    }

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

        Set<OrderingConstraint> constraints = resolveConstraints(goals);
        return CompilationResult.single(factory.of(nodes, deps, constraints));
    }

    private Set<OrderingConstraint> resolveConstraints(IoTGoals goals) {
        var entries = new LinkedHashSet<IoTOrderingEntry>();
        if (orderingLoader != null) {
            entries.addAll(orderingLoader.loadGlobal());
        }
        entries.addAll(goals.ordering());

        if (entries.isEmpty()) return Set.of();

        validateNoCycles(entries);

        var constraints = new HashSet<OrderingConstraint>();
        for (IoTOrderingEntry entry : entries) {
            constraints.add(new OrderingConstraint(
                IoTNodeTypes.configType(entry.before()), IoTNodeTypes.configType(entry.after())));
            constraints.add(new OrderingConstraint(
                IoTNodeTypes.physicalType(entry.before()), IoTNodeTypes.physicalType(entry.after())));
        }
        return Set.copyOf(constraints);
    }

    private void validateNoCycles(Set<IoTOrderingEntry> entries) {
        Map<DeviceClass, Set<DeviceClass>> adj = new HashMap<>();
        Map<DeviceClass, Integer> inDegree = new HashMap<>();
        for (IoTOrderingEntry entry : entries) {
            adj.computeIfAbsent(entry.before(), k -> new HashSet<>()).add(entry.after());
            inDegree.putIfAbsent(entry.before(), 0);
            inDegree.merge(entry.after(), 1, Integer::sum);
        }
        Queue<DeviceClass> queue = new ArrayDeque<>();
        for (var e : inDegree.entrySet()) {
            if (e.getValue() == 0) queue.add(e.getKey());
        }
        int processed = 0;
        while (!queue.isEmpty()) {
            var current = queue.poll();
            processed++;
            for (var next : adj.getOrDefault(current, Set.of())) {
                int newDeg = inDegree.merge(next, -1, Integer::sum);
                if (newDeg == 0) queue.add(next);
            }
        }
        if (processed != inDegree.size()) {
            throw new IllegalArgumentException("Ordering constraints contain a cycle");
        }
    }
}
