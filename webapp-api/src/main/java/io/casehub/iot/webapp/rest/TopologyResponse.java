package io.casehub.iot.webapp.rest;

import java.util.List;
import java.util.Map;

public record TopologyResponse(
        List<TopologyNode> nodes,
        List<TopologyEdge> edges,
        List<TopologyOrderingConstraint> orderingConstraints,
        Map<String, TopologyAggregate> locationAggregates
) {}
