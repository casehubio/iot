package io.casehub.iot.webapp.rest;

public record TopologyEdge(
        String sourceDeviceId,
        String targetDeviceId,
        String label
) {}
