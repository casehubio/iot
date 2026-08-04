package io.casehub.iot.webapp.resolution;

import io.casehub.iot.webapp.cbr.ResolutionSuggestion;

import java.util.List;
import java.util.Map;

public record QueueEntryDetail(
        QueueEntrySummary entry,
        Map<String, Object> workingContext,
        List<ResolutionSuggestion> suggestions,
        AiEscalationContext escalationContext,
        List<ExecutedActionResult> executionResults
) {
    public QueueEntryDetail {
        suggestions = suggestions != null ? List.copyOf(suggestions) : List.of();
        executionResults = executionResults != null ? List.copyOf(executionResults) : List.of();
        workingContext = workingContext != null ? Map.copyOf(workingContext) : Map.of();
    }
}
