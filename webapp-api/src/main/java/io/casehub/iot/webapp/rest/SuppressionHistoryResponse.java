package io.casehub.iot.webapp.rest;

import java.time.Instant;
import java.util.UUID;

public record SuppressionHistoryResponse(
        UUID id,
        String situationId,
        String correlationKey,
        String tier,
        double dismissalRate,
        int matchedCaseCount,
        Instant suppressedAt,
        boolean overridden) {}
