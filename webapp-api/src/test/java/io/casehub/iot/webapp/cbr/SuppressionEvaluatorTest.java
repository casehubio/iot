package io.casehub.iot.webapp.cbr;

import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.FeatureValue;
import io.casehub.neocortex.memory.cbr.FeatureVectorCbrCase;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SuppressionEvaluatorTest {

    private CbrCaseMemoryStore store;
    private SuppressionEvaluator evaluator;

    @BeforeEach
    void setUp() {
        store = mock(CbrCaseMemoryStore.class);
        evaluator = new SuppressionEvaluator(store, SuppressionConfig.defaults());
    }

    private Map<String, Object> features() {
        return Map.of("deviceClass", "thermostat", "roomType", "bedroom",
                "hourOfDay", 14.0, "dayType", "weekday", "season", "summer");
    }

    private List<ScoredCbrCase<FeatureVectorCbrCase>> mixedCases(
            int total, int dismissed, double avgScore) {
        var results = new ArrayList<ScoredCbrCase<FeatureVectorCbrCase>>();
        for (int i = 0; i < total; i++) {
            String outcome = i < dismissed ? "dismissed" : "actioned";
            var cbrCase = new FeatureVectorCbrCase(
                    "situation-dismissal", "n/a", outcome, null,
                    Map.of("deviceClass", FeatureValue.string("thermostat")));
            results.add(new ScoredCbrCase<>(cbrCase, "case-" + i, avgScore));
        }
        return results;
    }

    @Test
    void assess_noSimilarCases_returnsNone() {
        when(store.retrieveSimilar(any(), eq(FeatureVectorCbrCase.class)))
                .thenReturn(List.of());

        var result = evaluator.assess("temp-threshold", features(), "t1");

        assertThat(result.tier()).isEqualTo(SuppressionTier.NONE);
        assertThat(result.totalCases()).isZero();
        assertThat(result.dismissedCases()).isZero();
    }

    @Test
    void assess_belowMinCases_returnsNone() {
        when(store.retrieveSimilar(any(), eq(FeatureVectorCbrCase.class)))
                .thenReturn(mixedCases(3, 3, 1.0));

        var result = evaluator.assess("temp-threshold", features(), "t1");

        assertThat(result.tier()).isEqualTo(SuppressionTier.NONE);
    }

    @Test
    void assess_highDismissalRate_returnsSuppressTier() {
        when(store.retrieveSimilar(any(), eq(FeatureVectorCbrCase.class)))
                .thenReturn(mixedCases(10, 9, 0.8));

        var result = evaluator.assess("temp-threshold", features(), "t1");

        assertThat(result.tier()).isEqualTo(SuppressionTier.SUPPRESS);
        assertThat(result.dismissalRate()).isCloseTo(0.9, within(0.01));
        assertThat(result.totalCases()).isEqualTo(10);
        assertThat(result.dismissedCases()).isEqualTo(9);
    }

    @Test
    void assess_moderateDismissalRate_returnsDemoteTier() {
        when(store.retrieveSimilar(any(), eq(FeatureVectorCbrCase.class)))
                .thenReturn(mixedCases(10, 8, 0.7));

        var result = evaluator.assess("temp-threshold", features(), "t1");

        assertThat(result.tier()).isEqualTo(SuppressionTier.DEMOTE);
        assertThat(result.dismissalRate()).isCloseTo(0.8, within(0.01));
    }

    @Test
    void assess_lowDismissalRate_returnsAnnotateTier() {
        when(store.retrieveSimilar(any(), eq(FeatureVectorCbrCase.class)))
                .thenReturn(mixedCases(10, 3, 0.6));

        var result = evaluator.assess("temp-threshold", features(), "t1");

        assertThat(result.tier()).isEqualTo(SuppressionTier.ANNOTATE);
        assertThat(result.dismissalRate()).isCloseTo(0.3, within(0.01));
    }

    @Test
    void assess_zeroDismissals_returnsNone() {
        when(store.retrieveSimilar(any(), eq(FeatureVectorCbrCase.class)))
                .thenReturn(mixedCases(10, 0, 0.7));

        var result = evaluator.assess("temp-threshold", features(), "t1");

        assertThat(result.tier()).isEqualTo(SuppressionTier.NONE);
    }

    @Test
    void assess_exactlyAtFullThreshold_returnsSuppressTier() {
        when(store.retrieveSimilar(any(), eq(FeatureVectorCbrCase.class)))
                .thenReturn(mixedCases(10, 9, 0.8));

        var result = evaluator.assess("temp-threshold", features(), "t1");

        assertThat(result.tier()).isEqualTo(SuppressionTier.SUPPRESS);
    }

    @Test
    void assess_exactlyAtDemotionThreshold_returnsDemoteTier() {
        when(store.retrieveSimilar(any(), eq(FeatureVectorCbrCase.class)))
                .thenReturn(mixedCases(10, 7, 0.7));

        var result = evaluator.assess("temp-threshold", features(), "t1");

        assertThat(result.tier()).isEqualTo(SuppressionTier.DEMOTE);
    }

    @Test
    void assess_queriesWithCorrectCaseType() {
        when(store.retrieveSimilar(any(), eq(FeatureVectorCbrCase.class)))
                .thenReturn(List.of());

        evaluator.assess("motion-at-night", features(), "t1");

        var captor = ArgumentCaptor.forClass(CbrQuery.class);
        verify(store).retrieveSimilar(captor.capture(), eq(FeatureVectorCbrCase.class));
        assertThat(captor.getValue().caseType()).isEqualTo("iot-dismissal:motion-at-night");
        assertThat(captor.getValue().topK()).isEqualTo(20);
        assertThat(captor.getValue().minSimilarity()).isEqualTo(0.5);
    }

    @Test
    void assess_averageSimilarity_computed() {
        var cases = new ArrayList<ScoredCbrCase<FeatureVectorCbrCase>>();
        for (int i = 0; i < 5; i++) {
            var cbrCase = new FeatureVectorCbrCase(
                    "situation-dismissal", "n/a", "dismissed", null,
                    Map.of("deviceClass", FeatureValue.string("thermostat")));
            cases.add(new ScoredCbrCase<>(cbrCase, "case-" + i, 0.6 + i * 0.05));
        }
        when(store.retrieveSimilar(any(), eq(FeatureVectorCbrCase.class)))
                .thenReturn(cases);

        var result = evaluator.assess("temp-threshold", features(), "t1");

        assertThat(result.averageSimilarity()).isCloseTo(0.7, within(0.01));
    }

    @Test
    void assess_overrideActionedCountsAsActioned() {
        var cases = new ArrayList<ScoredCbrCase<FeatureVectorCbrCase>>();
        for (int i = 0; i < 5; i++) {
            var cbrCase = new FeatureVectorCbrCase(
                    "situation-dismissal", "n/a", "dismissed", null,
                    Map.of("deviceClass", FeatureValue.string("thermostat")));
            cases.add(new ScoredCbrCase<>(cbrCase, "case-" + i, 0.8));
        }
        for (int i = 5; i < 10; i++) {
            var cbrCase = new FeatureVectorCbrCase(
                    "situation-dismissal", "n/a", "override-actioned", null,
                    Map.of("deviceClass", FeatureValue.string("thermostat")));
            cases.add(new ScoredCbrCase<>(cbrCase, "case-" + i, 0.8));
        }
        when(store.retrieveSimilar(any(), eq(FeatureVectorCbrCase.class)))
                .thenReturn(cases);

        var result = evaluator.assess("temp-threshold", features(), "t1");

        assertThat(result.tier()).isEqualTo(SuppressionTier.ANNOTATE);
        assertThat(result.dismissalRate()).isCloseTo(0.5, within(0.01));
        assertThat(result.dismissedCases()).isEqualTo(5);
    }

    @Test
    void constructor_nullStoreThrows() {
        assertThatNullPointerException()
                .isThrownBy(() -> new SuppressionEvaluator(null, SuppressionConfig.defaults()));
    }

    @Test
    void constructor_nullConfigThrows() {
        assertThatNullPointerException()
                .isThrownBy(() -> new SuppressionEvaluator(mock(CbrCaseMemoryStore.class), null));
    }

    @Test
    void config_fullThresholdBelowDemotion_throws() {
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> new SuppressionConfig(0.5, 0.7, 5, 20, 0.5))
                .withMessageContaining("fullThreshold must be >= demotionThreshold");
    }

    @Test
    void config_minCasesZero_throws() {
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> new SuppressionConfig(0.9, 0.7, 0, 20, 0.5))
                .withMessageContaining("minCases must be >= 1");
    }
}
