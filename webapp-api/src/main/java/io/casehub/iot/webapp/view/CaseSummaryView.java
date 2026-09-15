package io.casehub.iot.webapp.view;

import java.time.Instant;
import java.util.UUID;

public record CaseSummaryView(
    UUID caseId,
    String caseType,
    String status,
    String situationId,
    int pendingActionsCount,
    Instant createdAt) {}
