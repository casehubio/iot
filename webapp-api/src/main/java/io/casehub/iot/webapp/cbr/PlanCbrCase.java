package io.casehub.iot.webapp.cbr;

import io.casehub.neocortex.cognitive.Confidence;
import io.casehub.neocortex.memory.cbr.CbrRecord;
import io.casehub.neocortex.memory.cbr.FeatureValue;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record PlanCbrCase(String problem, String solution,
                          String outcome, Confidence confidence,
                          Map<String, FeatureValue> features,
                          List<PlanTrace> planTrace) implements CbrRecord {
    public static final String CBR_TYPE = "plan";

    @Override
    public String recordType() { return CBR_TYPE; }

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
    public CbrRecord withOutcome(String outcome, Confidence confidence) {
        return new PlanCbrCase(problem, solution, outcome, confidence, features, planTrace);
    }
}
