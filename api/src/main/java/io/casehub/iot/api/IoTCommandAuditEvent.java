package io.casehub.iot.api;

import java.time.Instant;
import java.util.Map;

public record IoTCommandAuditEvent(
    String deviceId,
    String action,
    Map<String, Object> parameters,
    CommandResult result,
    String dispatchedBy,
    String correlationId,
    String providerId,
    Instant timestamp
) {}
