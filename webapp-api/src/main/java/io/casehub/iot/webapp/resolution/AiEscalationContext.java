package io.casehub.iot.webapp.resolution;

import io.casehub.iot.webapp.cbr.ResolutionSuggestion;

import java.util.List;

public record AiEscalationContext(
        String reason,
        List<ResolutionSuggestion> consideredSuggestions,
        String partialAnalysis,
        List<PlannedActionSpec> partialPlan,
        List<ExecutedActionResult> executedActions
) {}
