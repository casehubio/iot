package io.casehub.iot.webapp.app.service;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.ActualStateAdapter;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DriftDecision;
import io.casehub.desiredstate.api.DriftPolicy;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.RevertCondition;
import io.casehub.iot.api.DeviceEntity;
import io.casehub.iot.api.spi.DeviceRegistry;
import io.casehub.iot.webapp.rest.DriftStatus;
import io.casehub.iot.webapp.rest.TopologyAggregate;
import io.casehub.iot.webapp.rest.TopologyEdge;
import io.casehub.iot.webapp.rest.TopologyNode;
import io.casehub.iot.desiredstate.IoTNodeTypes;
import io.casehub.iot.webapp.rest.TopologyOrderingConstraint;
import io.casehub.iot.webapp.rest.TopologyResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class TopologyAssembler {

    private final DeviceRegistry deviceRegistry;
    private final Instance<DesiredStateGraph> desiredStateGraphInstance;
    private final Instance<ActualStateAdapter> actualStateAdapterInstance;
    private final Instance<DriftPolicy> driftPolicyInstance;

    @Inject
    public TopologyAssembler(DeviceRegistry deviceRegistry,
                             Instance<DesiredStateGraph> desiredStateGraphInstance,
                             Instance<ActualStateAdapter> actualStateAdapterInstance,
                             Instance<DriftPolicy> driftPolicyInstance) {
        this.deviceRegistry = deviceRegistry;
        this.desiredStateGraphInstance = desiredStateGraphInstance;
        this.actualStateAdapterInstance = actualStateAdapterInstance;
        this.driftPolicyInstance = driftPolicyInstance;
    }

    TopologyAssembler(DeviceRegistry deviceRegistry) {
        this(deviceRegistry, null, null, null);
    }

    public TopologyResponse assemble(String tenancyId) {
        List<DeviceEntity> devices = deviceRegistry.findAll().stream()
                .filter(d -> d.tenancyId().equals(tenancyId))
                .toList();

        DesiredStateGraph graph = resolveOptional(desiredStateGraphInstance);
        ActualState actualState = null;
        DriftPolicy policy = null;

        if (graph != null && !graph.isEmpty()) {
            var adapter = resolveOptional(actualStateAdapterInstance);
            if (adapter != null) {
                actualState = adapter.readActual(graph, tenancyId);
            }
            policy = resolveOptional(driftPolicyInstance);
        }

        List<TopologyNode> nodes = new ArrayList<>();
        List<TopologyEdge> edges = new ArrayList<>();

        for (DeviceEntity device : devices) {
            nodes.add(buildNode(device, graph, actualState, policy));
        }

        List<TopologyOrderingConstraint> orderingConstraints = new ArrayList<>();
        if (graph != null) {
            for (var dep : graph.dependencies()) {
                String from = dep.from().value();
                String to = dep.to().value();
                boolean fromExists = nodes.stream().anyMatch(n -> n.deviceId().equals(from));
                boolean toExists = nodes.stream().anyMatch(n -> n.deviceId().equals(to));
                if (fromExists && toExists) {
                    edges.add(new TopologyEdge(from, to, "depends-on"));
                }
            }

            var seen = new java.util.LinkedHashSet<>();
            for (var c : graph.orderingConstraints()) {
                String before = IoTNodeTypes.extractDeviceClass(c.before());
                String after = IoTNodeTypes.extractDeviceClass(c.after());
                String key = before + "->" + after;
                if (seen.add(key)) {
                    orderingConstraints.add(new TopologyOrderingConstraint(before, after));
                }
            }
        }

        Map<String, TopologyAggregate> aggregates = computeAggregates(nodes);
        return new TopologyResponse(List.copyOf(nodes), List.copyOf(edges),
            List.copyOf(orderingConstraints), Map.copyOf(aggregates));
    }

    public TopologyNode reassembleNode(String deviceId, String tenancyId) {
        DeviceEntity device = deviceRegistry.findById(deviceId, tenancyId)
                .orElseThrow(() -> new IllegalArgumentException("Device not found: " + deviceId));
        DesiredStateGraph graph = resolveOptional(desiredStateGraphInstance);
        ActualState actualState = null;
        DriftPolicy policy = null;
        if (graph != null && !graph.isEmpty()) {
            var adapter = resolveOptional(actualStateAdapterInstance);
            if (adapter != null) {
                actualState = adapter.readActual(graph, tenancyId);
            }
            policy = resolveOptional(driftPolicyInstance);
        }
        return buildNode(device, graph, actualState, policy);
    }

    private TopologyNode buildNode(DeviceEntity device, DesiredStateGraph graph,
                                    ActualState actualState, DriftPolicy policy) {
        List<String> locationPath = parseLocation(device.location());
        DriftStatus driftStatus = DriftStatus.UNMONITORED;
        String driftDetail = null;

        if (graph != null) {
            NodeId nodeId = NodeId.of(device.deviceId());
            DesiredNode desired = graph.nodes().get(nodeId);
            if (desired != null && actualState != null) {
                var status = actualState.statusOf(nodeId).orElse(NodeStatus.UNKNOWN);
                driftStatus = mapDriftStatus(status, nodeId, desired, policy);
                if (driftStatus == DriftStatus.PERMITTED_DRIFT && policy != null) {
                    var decision = policy.evaluate(nodeId, status, desired, null);
                    if (decision instanceof DriftDecision.Exempt exempt) {
                        driftDetail = formatRevertCondition(exempt.spec().revertCondition());
                    }
                }
            }
        }

        return new TopologyNode(
                device.deviceId(), device.label(),
                device.deviceClass().name(), locationPath,
                device.available(), device.lastUpdated(),
                driftStatus, driftDetail);
    }

    static List<String> parseLocation(String location) {
        if (location == null || location.isBlank()) return List.of();
        return Arrays.stream(location.split("/"))
                .filter(s -> !s.isBlank())
                .toList();
    }

    private DriftStatus mapDriftStatus(NodeStatus status, NodeId nodeId,
                                        DesiredNode desired, DriftPolicy policy) {
        return switch (status) {
            case PRESENT -> DriftStatus.CONVERGED;
            case DRIFTED -> {
                if (policy != null) {
                    var decision = policy.evaluate(nodeId, status, desired, null);
                    yield (decision instanceof DriftDecision.Exempt)
                            ? DriftStatus.PERMITTED_DRIFT
                            : DriftStatus.UNEXPECTED_DRIFT;
                }
                yield DriftStatus.UNEXPECTED_DRIFT;
            }
            case ABSENT -> DriftStatus.ABSENT;
            case UNKNOWN, SUSPENDED -> DriftStatus.UNKNOWN;
        };
    }

    private String formatRevertCondition(RevertCondition condition) {
        return switch (condition) {
            case RevertCondition.OnDuration d -> "revert in " + d.duration().toMinutes() + "m";
            case RevertCondition.OnSchedule s -> "revert on schedule";
            case RevertCondition.OnStatusChange e -> "revert on status change";
            case RevertCondition.Never n -> "drift: never";
        };
    }

    private Map<String, TopologyAggregate> computeAggregates(List<TopologyNode> nodes) {
        Map<String, int[]> counts = new HashMap<>();
        for (TopologyNode node : nodes) {
            List<String> path = node.locationPath();
            for (int i = 1; i <= path.size(); i++) {
                String key = String.join("/", path.subList(0, i));
                int[] c = counts.computeIfAbsent(key, k -> new int[7]);
                c[0]++;
                switch (node.driftStatus()) {
                    case CONVERGED -> c[1]++;
                    case PERMITTED_DRIFT -> c[2]++;
                    case UNEXPECTED_DRIFT -> c[3]++;
                    case ABSENT -> c[4]++;
                    case UNKNOWN -> c[5]++;
                    case UNMONITORED -> c[6]++;
                }
            }
        }
        Map<String, TopologyAggregate> result = new HashMap<>();
        counts.forEach((key, c) -> result.put(key,
                new TopologyAggregate(c[0], c[1], c[2], c[3], c[4], c[5], c[6])));
        return result;
    }

    private static <T> T resolveOptional(Instance<T> instance) {
        if (instance == null || instance.isUnsatisfied()) return null;
        return instance.get();
    }
}
