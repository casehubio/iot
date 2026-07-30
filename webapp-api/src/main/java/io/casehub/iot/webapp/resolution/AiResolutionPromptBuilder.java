package io.casehub.iot.webapp.resolution;

import io.casehub.iot.webapp.cbr.ResolutionSuggestion;
import io.casehub.neocortex.memory.cbr.PlanTrace;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class AiResolutionPromptBuilder {

    private AiResolutionPromptBuilder() {}

    public static String build(Map<String, Object> caseContext,
                               List<ResolutionSuggestion> suggestions,
                               Set<String> availableActions) {
        var sb = new StringBuilder();

        sb.append("## Current Situation\n\n");
        caseContext.forEach((key, value) ->
            sb.append("- **").append(key).append(":** ").append(value).append("\n"));

        sb.append("\n## Past Resolutions\n\n");
        if (suggestions.isEmpty()) {
            sb.append("No similar past cases found.\n");
        } else {
            for (int i = 0; i < suggestions.size(); i++) {
                var s = suggestions.get(i);
                sb.append("### Match ").append(i + 1)
                  .append(" (similarity: ").append(s.similarityScore()).append(")\n");
                sb.append("- **Problem:** ").append(s.problem()).append("\n");
                sb.append("- **Solution:** ").append(s.solution()).append("\n");
                sb.append("- **Outcome:** ").append(s.outcome()).append("\n");
                if (s.confidence() != null) {
                    sb.append("- **Confidence:** ").append(s.confidence()).append("\n");
                }
                if (!s.planSteps().isEmpty()) {
                    sb.append("- **Plan steps:**\n");
                    for (PlanTrace step : s.planSteps()) {
                        sb.append("  - ").append(step.workerName())
                          .append(" (").append(step.capabilityName()).append(")")
                          .append(" → ").append(step.stepOutcome()).append("\n");
                    }
                }
                sb.append("\n");
            }
        }

        sb.append("## Available Actions\n\n");
        if (availableActions.isEmpty()) {
            sb.append("No autonomous actions available. You must ESCALATE.\n");
        } else {
            sb.append("You may use these action types: ");
            sb.append(availableActions.stream().sorted().collect(Collectors.joining(", ")));
            sb.append("\n");
        }

        sb.append("\n## Instructions\n\n");
        sb.append("Decide whether to EXECUTE a resolution plan or ESCALATE to a human operator.\n");
        sb.append("If you have high confidence based on past resolutions, produce a plan.\n");
        sb.append("If the situation is novel or risky, ESCALATE.\n");

        return sb.toString();
    }
}
