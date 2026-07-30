package io.casehub.iot.webapp.resolution;

import java.util.Objects;

public record ExecutedActionResult(
        PlannedActionSpec action,
        boolean succeeded,
        String outcome
) {
    public ExecutedActionResult {
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
    }
}
