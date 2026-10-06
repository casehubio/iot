package io.casehub.iot.webapp.app.service;

import io.casehub.iot.webapp.rest.TopologyNode;

import java.util.List;
import java.util.Map;

public record TopologyStreamEvent(
        String operation,
        List<TopologyNode> nodes,
        Map<String, Object> binding
) {
    public TopologyStreamEvent(String operation, List<TopologyNode> nodes) {
        this(operation, nodes, null);
    }

    public static TopologyStreamEvent binding(String operation, Map<String, Object> data) {
        return new TopologyStreamEvent(operation, List.of(), data);
    }
}
