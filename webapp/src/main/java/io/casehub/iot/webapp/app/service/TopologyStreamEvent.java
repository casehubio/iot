package io.casehub.iot.webapp.app.service;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.casehub.iot.webapp.rest.TopologyNode;

import java.util.List;
import java.util.Map;

public record TopologyStreamEvent(
        @JsonIgnore String tenancyId,
        String operation,
        List<TopologyNode> nodes,
        Map<String, Object> binding
) {
    public TopologyStreamEvent(String tenancyId, String operation, List<TopologyNode> nodes) {
        this(tenancyId, operation, nodes, null);
    }

    public static TopologyStreamEvent binding(String tenancyId, String operation, Map<String, Object> data) {
        return new TopologyStreamEvent(tenancyId, operation, List.of(), data);
    }
}
