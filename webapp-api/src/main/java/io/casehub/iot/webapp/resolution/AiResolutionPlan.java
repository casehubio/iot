package io.casehub.iot.webapp.resolution;

import java.util.List;
import java.util.Objects;

public record AiResolutionPlan(
        Decision decision,
        String reasoning,
        List<PlannedActionSpec> actions,
        String escalationReason
) {
    public AiResolutionPlan {
        Objects.requireNonNull(decision, "decision must not be null");
        Objects.requireNonNull(reasoning, "reasoning must not be null");
        actions = actions != null ? List.copyOf(actions) : List.of();
    }
}
