package io.casehub.iot.webapp.rest;

import java.time.Instant;
import java.util.List;

public record TopologyNode(
        String deviceId,
        String label,
        String deviceClass,
        List<String> locationPath,
        boolean available,
        Instant lastUpdated,
        DriftStatus driftStatus,
        String driftDetail
) {}
