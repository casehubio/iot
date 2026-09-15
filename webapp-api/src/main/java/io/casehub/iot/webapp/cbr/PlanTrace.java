package io.casehub.iot.webapp.cbr;

import java.util.Map;
import java.util.Objects;

public record PlanTrace(String bindingName, String capabilityName,
                        String workerName, String stepOutcome,
                        int priority, Map<String, Object> parameters) {
    public PlanTrace {
        Objects.requireNonNull(bindingName, "bindingName required");
        if (priority < 0) throw new IllegalArgumentException("priority must be >= 0, got: " + priority);
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }
}
