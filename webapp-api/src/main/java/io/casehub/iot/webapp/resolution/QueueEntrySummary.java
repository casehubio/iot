package io.casehub.iot.webapp.resolution;

import java.time.Instant;
import java.util.UUID;

public record QueueEntrySummary(
        UUID entryId,
        UUID caseId,
        String caseType,
        String viewName,
        String status,
        String assignedTo,
        Instant createdAt,
        Instant claimedAt,
        Instant escalatedAt,
        String previousViewName,
        String deviceId,
        String deviceClass,
        String roomType,
        String situationId
) {}
