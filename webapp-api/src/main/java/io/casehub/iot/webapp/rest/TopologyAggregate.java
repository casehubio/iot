package io.casehub.iot.webapp.rest;

public record TopologyAggregate(
        int total,
        int converged,
        int permittedDrift,
        int unexpectedDrift,
        int absent,
        int unknown,
        int unmonitored
) {}
