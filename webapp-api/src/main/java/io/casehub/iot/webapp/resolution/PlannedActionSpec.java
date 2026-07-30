package io.casehub.iot.webapp.resolution;

import java.util.Map;
import java.util.Objects;

public record PlannedActionSpec(
        String actionType,
        String targetDeviceId,
        Map<String, Object> parameters,
        String rationale
) {
    public PlannedActionSpec {
        Objects.requireNonNull(actionType, "actionType must not be null");
        Objects.requireNonNull(targetDeviceId, "targetDeviceId must not be null");
        parameters = parameters != null ? Map.copyOf(parameters) : Map.of();
    }
}
