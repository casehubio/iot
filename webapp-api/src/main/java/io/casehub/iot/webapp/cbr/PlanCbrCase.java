package io.casehub.iot.webapp.cbr;

import io.casehub.neocortex.cognitive.Confidence;
import io.casehub.neocortex.memory.cbr.CbrCase;
import io.casehub.neocortex.memory.cbr.FeatureValue;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record PlanCbrCase(String problem, String solution,
                          String outcome, Confidence confidence,
                          Map<String, FeatureValue> features,
                          List<PlanTrace> planTrace) implements CbrCase {
    public static final String CBR_TYPE = "plan";

    @Override
    public String cbrType() { return CBR_TYPE; }

    public PlanCbrCase {
        Objects.requireNonNull(problem, "problem required");
        Objects.requireNonNull(solution, "solution required");
        Objects.requireNonNull(features, "features required");
        features = Map.copyOf(features);
        Objects.requireNonNull(planTrace, "planTrace required");
        planTrace = List.copyOf(planTrace);
    }

    @Override
    public Map<String, FeatureValue> features() { return features; }

    @Override
    public CbrCase withOutcome(String outcome, Confidence confidence) {
        return new PlanCbrCase(problem, solution, outcome, confidence, features, planTrace);
    }
}
