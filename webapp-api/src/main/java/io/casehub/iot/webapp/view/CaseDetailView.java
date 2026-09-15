package io.casehub.iot.webapp.view;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CaseDetailView(
    UUID caseId,
    String caseType,
    String status,
    String situationId,
    List<CaseEventView> events,
    List<WorkerResultView> workerResults,
    List<PlannedActionView> plannedActions,
    Instant createdAt,
    Instant completedAt) {

    public record CaseEventView(String type, String detail, Instant occurredAt) {}
    public record WorkerResultView(String workerId, String outcome, Object data) {}
    public record PlannedActionView(String action, String status, Object parameters) {}
}
