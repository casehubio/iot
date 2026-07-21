package io.casehub.iot.webapp.cbr;

import io.casehub.platform.api.expression.LambdaExpression;
import io.casehub.platform.api.label.LabelAction;
import io.casehub.platform.api.label.LabelRule;

import java.util.List;
import java.util.Map;

public final class IoTTriageLabelRules {

    static final double MEDIUM_FLOOR_SIMILARITY = 0.5;

    private IoTTriageLabelRules() {}

    public static List<LabelRule> cbrTriageRules(
            double aiMinSimilarity, double aiMinConsistency) {
        return List.of(
            new LabelRule("cbr-high",
                new LambdaExpression<>(ctx -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = (Map<String, Object>) ctx;
                    double sim = doubleOr(map, "cbrBestSimilarity", 0.0);
                    double con = doubleOr(map, "cbrOutcomeConsistency", 0.0);
                    return sim >= aiMinSimilarity && con >= aiMinConsistency;
                }),
                List.of(new LabelAction.Add("iot-triage:ai-resolution"))),
            new LabelRule("cbr-medium",
                new LambdaExpression<>(ctx -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = (Map<String, Object>) ctx;
                    double sim = doubleOr(map, "cbrBestSimilarity", 0.0);
                    double con = doubleOr(map, "cbrOutcomeConsistency", 0.0);
                    return sim >= MEDIUM_FLOOR_SIMILARITY
                        && !(sim >= aiMinSimilarity && con >= aiMinConsistency);
                }),
                List.of(new LabelAction.Add("iot-triage:operator-assisted"))),
            new LabelRule("cbr-low-or-none",
                new LambdaExpression<>(ctx -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = (Map<String, Object>) ctx;
                    return doubleOr(map, "cbrBestSimilarity", 0.0) < MEDIUM_FLOOR_SIMILARITY;
                }),
                List.of(new LabelAction.Add("iot-triage:operator-manual")))
        );
    }

    private static double doubleOr(Map<String, Object> ctx, String key, double def) {
        Object v = ctx.get(key);
        return v instanceof Number n ? n.doubleValue() : def;
    }
}
