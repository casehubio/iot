package io.casehub.iot.webapp.cbr;

// PlanTrace relocated from neocortex.memory.cbr to local package

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ResolutionSuggestion(
        String caseId,
        double similarityScore,
        String problem,
        String solution,
        String outcome,
        Double confidence,
        Map<String, Object> matchedFeatures,
        Map<String, Double> featureSimilarities,
        List<PlanTrace> planSteps
) {
    public ResolutionSuggestion {
        Objects.requireNonNull(problem, "problem must not be null");
        Objects.requireNonNull(solution, "solution must not be null");
        if (similarityScore < 0.0 || similarityScore > 1.0) {
            throw new IllegalArgumentException(
                    "similarityScore must be in [0, 1], got: " + similarityScore);
        }
        if (confidence != null && (confidence < 0.0 || confidence > 1.0)) {
            throw new IllegalArgumentException(
                    "confidence must be in [0, 1], got: " + confidence);
        }
        matchedFeatures = matchedFeatures != null ? Map.copyOf(matchedFeatures) : Map.of();
        featureSimilarities = featureSimilarities != null ? Map.copyOf(featureSimilarities) : Map.of();
        planSteps = planSteps != null ? List.copyOf(planSteps) : List.of();
    }
}
