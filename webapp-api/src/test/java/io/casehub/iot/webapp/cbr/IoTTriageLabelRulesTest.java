package io.casehub.iot.webapp.cbr;

import io.casehub.platform.api.label.LabelAction;
import io.casehub.platform.api.label.LabelRule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IoTTriageLabelRulesTest {

    private final List<LabelRule> rules = IoTTriageLabelRules.cbrTriageRules(0.85, 0.80);

    @Test
    void highConfidence_routesToAiResolution() {
        Map<String, Object> ctx = Map.of(
            "cbrBestSimilarity", 0.90,
            "cbrOutcomeConsistency", 0.85,
            "cbrMatchCount", 5);
        List<LabelAction> actions = LabelRule.evaluate(rules, ctx);
        assertThat(actions).hasSize(1);
        assertThat(actions.get(0)).isInstanceOf(LabelAction.Add.class);
        assertThat(((LabelAction.Add) actions.get(0)).label()).isEqualTo("iot-triage:ai-resolution");
    }

    @Test
    void mediumConfidence_routesToOperatorAssisted() {
        Map<String, Object> ctx = Map.of(
            "cbrBestSimilarity", 0.70,
            "cbrOutcomeConsistency", 0.60,
            "cbrMatchCount", 3);
        List<LabelAction> actions = LabelRule.evaluate(rules, ctx);
        assertThat(actions).hasSize(1);
        assertThat(((LabelAction.Add) actions.get(0)).label()).isEqualTo("iot-triage:operator-assisted");
    }

    @Test
    void lowConfidence_routesToOperatorManual() {
        Map<String, Object> ctx = Map.of(
            "cbrBestSimilarity", 0.30,
            "cbrOutcomeConsistency", 1.0,
            "cbrMatchCount", 1);
        List<LabelAction> actions = LabelRule.evaluate(rules, ctx);
        assertThat(actions).hasSize(1);
        assertThat(((LabelAction.Add) actions.get(0)).label()).isEqualTo("iot-triage:operator-manual");
    }

    @Test
    void noCbrData_routesToOperatorManual() {
        Map<String, Object> ctx = Map.of();
        List<LabelAction> actions = LabelRule.evaluate(rules, ctx);
        assertThat(actions).hasSize(1);
        assertThat(((LabelAction.Add) actions.get(0)).label()).isEqualTo("iot-triage:operator-manual");
    }

    @Test
    void atExactAiThreshold_routesToAiResolution() {
        Map<String, Object> ctx = Map.of(
            "cbrBestSimilarity", 0.85,
            "cbrOutcomeConsistency", 0.80,
            "cbrMatchCount", 4);
        List<LabelAction> actions = LabelRule.evaluate(rules, ctx);
        assertThat(actions).hasSize(1);
        assertThat(((LabelAction.Add) actions.get(0)).label()).isEqualTo("iot-triage:ai-resolution");
    }

    @Test
    void highSimilarity_lowConsistency_routesToOperatorAssisted() {
        Map<String, Object> ctx = Map.of(
            "cbrBestSimilarity", 0.90,
            "cbrOutcomeConsistency", 0.50,
            "cbrMatchCount", 4);
        List<LabelAction> actions = LabelRule.evaluate(rules, ctx);
        assertThat(actions).hasSize(1);
        assertThat(((LabelAction.Add) actions.get(0)).label()).isEqualTo("iot-triage:operator-assisted");
    }

    @Test
    void atMediumFloor_routesToOperatorAssisted() {
        Map<String, Object> ctx = Map.of(
            "cbrBestSimilarity", 0.5,
            "cbrOutcomeConsistency", 0.0,
            "cbrMatchCount", 2);
        List<LabelAction> actions = LabelRule.evaluate(rules, ctx);
        assertThat(actions).hasSize(1);
        assertThat(((LabelAction.Add) actions.get(0)).label()).isEqualTo("iot-triage:operator-assisted");
    }

    @Test
    void justBelowMediumFloor_routesToOperatorManual() {
        Map<String, Object> ctx = Map.of(
            "cbrBestSimilarity", 0.499,
            "cbrOutcomeConsistency", 1.0,
            "cbrMatchCount", 5);
        List<LabelAction> actions = LabelRule.evaluate(rules, ctx);
        assertThat(actions).hasSize(1);
        assertThat(((LabelAction.Add) actions.get(0)).label()).isEqualTo("iot-triage:operator-manual");
    }

    @Test
    void exactlyOneRuleFires_mutualExclusivity() {
        List<Map<String, Object>> contexts = List.of(
            Map.of("cbrBestSimilarity", 0.95, "cbrOutcomeConsistency", 0.90, "cbrMatchCount", 5),
            Map.of("cbrBestSimilarity", 0.70, "cbrOutcomeConsistency", 0.60, "cbrMatchCount", 3),
            Map.of("cbrBestSimilarity", 0.30, "cbrOutcomeConsistency", 1.0, "cbrMatchCount", 1),
            Map.of());
        for (Map<String, Object> ctx : contexts) {
            List<LabelAction> actions = LabelRule.evaluate(rules, ctx);
            assertThat(actions).as("context: " + ctx).hasSize(1);
        }
    }
}
