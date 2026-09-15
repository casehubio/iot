package io.casehub.iot.webapp.view;

import java.time.Instant;
import java.util.List;

public record AuditTrailView(
    List<AuditRecord> records,
    int totalCount,
    int offset,
    int limit) {

    public record AuditRecord(
        String eventType,
        String deviceId,
        String correlationId,
        Object payload,
        Instant occurredAt) {}
}
