package io.casehub.iot.webapp.rest;

public record SuppressionStatsResponse(
        String situationId,
        int suppressedCount,
        int demotedCount,
        int overrideCount,
        double currentDismissalRate,
        boolean safetyCritical) {}
