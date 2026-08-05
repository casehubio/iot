package io.casehub.iot.webapp.cbr;

import io.casehub.neocortex.memory.cbr.PlanTrace;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResolutionSuggestionTest {

    @Test
    void constructsWithAllFields() {
        var planStep = new PlanTrace("bind-1", "device-control", "set-temperature",
                "SUCCESS", 1, Map.of("target", 22), null);
        var suggestion = new ResolutionSuggestion(
                "case-123", 0.87, "Temperature rise", "Replaced filter",
                "RESOLVED", 0.95,
                Map.of("deviceClass", "thermostat"),
                Map.of("deviceClass", 1.0),
                List.of(planStep));

        assertThat(suggestion.caseId()).isEqualTo("case-123");
        assertThat(suggestion.similarityScore()).isEqualTo(0.87);
        assertThat(suggestion.problem()).isEqualTo("Temperature rise");
        assertThat(suggestion.solution()).isEqualTo("Replaced filter");
        assertThat(suggestion.outcome()).isEqualTo("RESOLVED");
        assertThat(suggestion.confidence()).isEqualTo(0.95);
        assertThat(suggestion.planSteps()).hasSize(1);
    }

    @Test
    void nullProblemThrows() {
        assertThatThrownBy(() -> new ResolutionSuggestion(
                "case-1", 0.5, null, "solution", "RESOLVED", null,
                Map.of(), Map.of(), List.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullSolutionThrows() {
        assertThatThrownBy(() -> new ResolutionSuggestion(
                "case-1", 0.5, "problem", null, "RESOLVED", null,
                Map.of(), Map.of(), List.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void similarityScoreAboveOneThrows() {
        assertThatThrownBy(() -> new ResolutionSuggestion(
                "case-1", 1.5, "problem", "solution", "RESOLVED", null,
                Map.of(), Map.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void similarityScoreBelowZeroThrows() {
        assertThatThrownBy(() -> new ResolutionSuggestion(
                "case-1", -0.1, "problem", "solution", "RESOLVED", null,
                Map.of(), Map.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void confidenceAboveOneThrows() {
        assertThatThrownBy(() -> new ResolutionSuggestion(
                "case-1", 0.5, "problem", "solution", "RESOLVED", 1.5,
                Map.of(), Map.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullOutcomeAllowed() {
        var suggestion = new ResolutionSuggestion(
                "case-1", 0.5, "problem", "solution", null, null,
                Map.of(), Map.of(), List.of());
        assertThat(suggestion.outcome()).isNull();
    }

    @Test
    void nullCaseIdAllowed() {
        var suggestion = new ResolutionSuggestion(
                null, 0.5, "problem", "solution", "RESOLVED", null,
                Map.of(), Map.of(), List.of());
        assertThat(suggestion.caseId()).isNull();
    }

    @Test
    void nullConfidenceAllowed() {
        var suggestion = new ResolutionSuggestion(
                "case-1", 0.5, "problem", "solution", "RESOLVED", null,
                Map.of(), Map.of(), List.of());
        assertThat(suggestion.confidence()).isNull();
    }

    @Test
    void emptyPlanStepsAllowed() {
        var suggestion = new ResolutionSuggestion(
                "case-1", 0.5, "problem", "solution", "RESOLVED", null,
                Map.of(), Map.of(), List.of());
        assertThat(suggestion.planSteps()).isEmpty();
    }

    @Test
    void matchedFeaturesDefensivelyCopied() {
        var features = new HashMap<String, Object>();
        features.put("key", "val");
        var suggestion = new ResolutionSuggestion(
                "case-1", 0.5, "problem", "solution", "RESOLVED", null,
                features, Map.of(), List.of());
        assertThatThrownBy(() -> suggestion.matchedFeatures().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void featureSimilaritiesDefensivelyCopied() {
        var sims = new HashMap<String, Double>();
        sims.put("key", 1.0);
        var suggestion = new ResolutionSuggestion(
                "case-1", 0.5, "problem", "solution", "RESOLVED", null,
                Map.of(), sims, List.of());
        assertThatThrownBy(() -> suggestion.featureSimilarities().put("x", 0.5))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nullMatchedFeaturesDefaultsToEmpty() {
        var suggestion = new ResolutionSuggestion(
                "case-1", 0.5, "problem", "solution", "RESOLVED", null,
                null, null, null);
        assertThat(suggestion.matchedFeatures()).isEmpty();
        assertThat(suggestion.featureSimilarities()).isEmpty();
        assertThat(suggestion.planSteps()).isEmpty();
    }

    @Test
    void boundaryScoreZeroAllowed() {
        var suggestion = new ResolutionSuggestion(
                "case-1", 0.0, "problem", "solution", "RESOLVED", null,
                Map.of(), Map.of(), List.of());
        assertThat(suggestion.similarityScore()).isEqualTo(0.0);
    }

    @Test
    void boundaryScoreOneAllowed() {
        var suggestion = new ResolutionSuggestion(
                "case-1", 1.0, "problem", "solution", "RESOLVED", null,
                Map.of(), Map.of(), List.of());
        assertThat(suggestion.similarityScore()).isEqualTo(1.0);
    }
}
