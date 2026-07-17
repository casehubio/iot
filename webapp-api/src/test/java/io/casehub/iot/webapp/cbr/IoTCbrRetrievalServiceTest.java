package io.casehub.iot.webapp.cbr;

import io.casehub.api.model.cbr.CbrConfig;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.FeatureValue;
import io.casehub.neocortex.memory.cbr.PlanCbrCase;
import io.casehub.neocortex.memory.cbr.PlanTrace;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class IoTCbrRetrievalServiceTest {

    private CbrCaseMemoryStore store;
    private IoTCbrRetrievalService service;

    @BeforeEach
    void setUp() {
        store = mock(CbrCaseMemoryStore.class);
        service = new IoTCbrRetrievalService(store);
    }

    private CbrConfig hvacConfig() {
        return CbrConfig.builder()
                .domain("iot")
                .caseType("hvac-anomaly")
                .featureExtractor(ctx -> Map.of())
                .weight("deviceClass", 2.0)
                .weight("roomType", 1.5)
                .topK(5)
                .minSimilarity(0.3)
                .vectorWeight(0.0)
                .build();
    }

    private CbrConfig hvacConfigWithDecay(int halfLifeDays) {
        return CbrConfig.builder()
                        .domain("iot")
                        .caseType("hvac-anomaly")
                        .featureExtractor(ctx -> Map.of())
                        .weight("deviceClass", 2.0)
                        .topK(5)
                        .minSimilarity(0.3)
                        .vectorWeight(0.0)
                        .temporalDecayHalfLifeDays(halfLifeDays)
                        .build();
    }

    @Test
    void retrieve_appliesTemporalDecayWhenConfigured() {
        when(store.retrieveSimilar(any(), eq(PlanCbrCase.class)))
                .thenReturn(List.of());

        service.retrieve(hvacConfigWithDecay(30), Map.of("deviceClass", "thermostat"), "t1");

        var captor = org.mockito.ArgumentCaptor.forClass(CbrQuery.class);
        verify(store).retrieveSimilar(captor.capture(), eq(PlanCbrCase.class));

        CbrQuery query = captor.getValue();
        assertThat(query.temporalDecay()).isNotNull();
        assertThat(query.temporalDecay()).isInstanceOf(io.casehub.neocortex.memory.cbr.TemporalDecay.HalfLife.class);
        var halfLife = (io.casehub.neocortex.memory.cbr.TemporalDecay.HalfLife) query.temporalDecay();
        assertThat(halfLife.halfLife()).isEqualTo(java.time.Duration.ofDays(30));
    }

    @Test
    void retrieve_noTemporalDecayWhenNotConfigured() {
        when(store.retrieveSimilar(any(), eq(PlanCbrCase.class)))
                .thenReturn(List.of());

        service.retrieve(hvacConfig(), Map.of("deviceClass", "thermostat"), "t1");

        var captor = org.mockito.ArgumentCaptor.forClass(CbrQuery.class);
        verify(store).retrieveSimilar(captor.capture(), eq(PlanCbrCase.class));

        assertThat(captor.getValue().temporalDecay()).isNull();
    }


    @Test
    void retrieve_buildsCbrQueryFromConfig() {
        when(store.retrieveSimilar(any(), eq(PlanCbrCase.class)))
                .thenReturn(List.of());

        var features = Map.<String, Object>of("deviceClass", "thermostat", "roomType", "bedroom");
        service.retrieve(hvacConfig(), features, "tenant-1");

        var captor = ArgumentCaptor.forClass(CbrQuery.class);
        verify(store).retrieveSimilar(captor.capture(), eq(PlanCbrCase.class));

        var query = captor.getValue();
        assertThat(query.tenantId()).isEqualTo("tenant-1");
        assertThat(query.domain().name()).isEqualTo("iot");
        assertThat(query.caseType()).isEqualTo("hvac-anomaly");
        assertThat(query.topK()).isEqualTo(5);
        assertThat(query.minSimilarity()).isEqualTo(0.3);
        assertThat(query.weights()).containsEntry("deviceClass", 2.0);
        assertThat(query.weights()).containsEntry("roomType", 1.5);
        assertThat(query.vectorWeight()).isEqualTo(0.0);
    }

    @Test
    void retrieve_convertsRawFeaturesToFeatureValues() {
        when(store.retrieveSimilar(any(), eq(PlanCbrCase.class)))
                .thenReturn(List.of());

        var features = Map.<String, Object>of("deviceClass", "thermostat", "hourOfDay", 14.0);
        service.retrieve(hvacConfig(), features, "tenant-1");

        var captor = ArgumentCaptor.forClass(CbrQuery.class);
        verify(store).retrieveSimilar(captor.capture(), eq(PlanCbrCase.class));

        var queryFeatures = captor.getValue().features();
        assertThat(queryFeatures.get("deviceClass")).isEqualTo(FeatureValue.string("thermostat"));
        assertThat(queryFeatures.get("hourOfDay")).isEqualTo(FeatureValue.number(14.0));
    }

    @Test
    void retrieve_mapsScoredCasesToSuggestions() {
        var planTrace = new PlanTrace("bind-1", "device-control", "set-temp",
                "SUCCESS", 1, Map.of());
        var cbrCase = new PlanCbrCase(
                "Temperature spike", "Replaced filter", "RESOLVED", 0.95,
                Map.of("deviceClass", FeatureValue.string("thermostat")),
                List.of(planTrace));
        var scored = new ScoredCbrCase<>(cbrCase, "past-case-1", 0.87, false,
                Map.of("deviceClass", 1.0, "roomType", 0.8), null, null);

        when(store.retrieveSimilar(any(), eq(PlanCbrCase.class)))
                .thenReturn(List.of(scored));

        var results = service.retrieve(hvacConfig(), Map.of("deviceClass", "thermostat"), "t1");

        assertThat(results).hasSize(1);
        var suggestion = results.getFirst();
        assertThat(suggestion.caseId()).isEqualTo("past-case-1");
        assertThat(suggestion.similarityScore()).isEqualTo(0.87);
        assertThat(suggestion.problem()).isEqualTo("Temperature spike");
        assertThat(suggestion.solution()).isEqualTo("Replaced filter");
        assertThat(suggestion.outcome()).isEqualTo("RESOLVED");
        assertThat(suggestion.confidence()).isEqualTo(0.95);
        assertThat(suggestion.planSteps()).hasSize(1);
        assertThat(suggestion.planSteps().getFirst().capabilityName()).isEqualTo("device-control");
        assertThat(suggestion.featureSimilarities()).containsEntry("deviceClass", 1.0);
    }

    @Test
    void retrieve_matchedFeaturesConvertedToRawValues() {
        var cbrCase = new PlanCbrCase(
                "problem", "solution", "RESOLVED", null,
                Map.of("deviceClass", FeatureValue.string("thermostat"),
                        "hourOfDay", FeatureValue.number(14.0)),
                List.of());
        var scored = new ScoredCbrCase<>(cbrCase, "c1", 0.5);

        when(store.retrieveSimilar(any(), eq(PlanCbrCase.class)))
                .thenReturn(List.of(scored));

        var results = service.retrieve(hvacConfig(), Map.of("deviceClass", "thermostat"), "t1");
        assertThat(results.getFirst().matchedFeatures())
                .containsEntry("deviceClass", "thermostat")
                .containsEntry("hourOfDay", 14.0);
    }

    @Test
    void retrieve_emptyFeatures_returnsEmptyWithoutCallingStore() {
        var results = service.retrieve(hvacConfig(), Map.of(), "t1");
        assertThat(results).isEmpty();
        verifyNoInteractions(store);
    }

    @Test
    void retrieve_nullFeatures_returnsEmptyWithoutCallingStore() {
        var results = service.retrieve(hvacConfig(), null, "t1");
        assertThat(results).isEmpty();
        verifyNoInteractions(store);
    }

    @Test
    void retrieve_featureOnlyMode_whenVectorWeightZero() {
        when(store.retrieveSimilar(any(), eq(PlanCbrCase.class)))
                .thenReturn(List.of());

        service.retrieve(hvacConfig(), Map.of("deviceClass", "thermostat"), "t1");

        var captor = ArgumentCaptor.forClass(CbrQuery.class);
        verify(store).retrieveSimilar(captor.capture(), eq(PlanCbrCase.class));
        assertThat(captor.getValue().retrievalMode().name()).isEqualTo("FEATURE_ONLY");
    }

    @Test
    void retrieve_hybridMode_whenVectorWeightPositive() {
        var config = CbrConfig.builder()
                .domain("iot")
                .caseType("hvac-anomaly")
                .featureExtractor(ctx -> Map.of())
                .topK(5)
                .minSimilarity(0.3)
                .vectorWeight(0.5)
                .build();

        when(store.retrieveSimilar(any(), eq(PlanCbrCase.class)))
                .thenReturn(List.of());

        service.retrieve(config, Map.of("deviceClass", "thermostat"), "t1");

        var captor = ArgumentCaptor.forClass(CbrQuery.class);
        verify(store).retrieveSimilar(captor.capture(), eq(PlanCbrCase.class));
        assertThat(captor.getValue().retrievalMode().name()).isEqualTo("HYBRID");
    }

    @Test
    void retrieve_multipleResults_preservesStoreOrder() {
        var case1 = new PlanCbrCase("p1", "s1", "R", null,
                Map.of("d", FeatureValue.string("a")), List.of());
        var case2 = new PlanCbrCase("p2", "s2", "R", null,
                Map.of("d", FeatureValue.string("b")), List.of());

        when(store.retrieveSimilar(any(), eq(PlanCbrCase.class)))
                .thenReturn(List.of(
                        new ScoredCbrCase<>(case1, "c1", 0.9),
                        new ScoredCbrCase<>(case2, "c2", 0.7)));

        var results = service.retrieve(hvacConfig(), Map.of("d", "x"), "t1");
        assertThat(results).hasSize(2);
        assertThat(results.get(0).similarityScore()).isEqualTo(0.9);
        assertThat(results.get(1).similarityScore()).isEqualTo(0.7);
    }

    @Test
    void retrieve_nullConfigThrows() {
        assertThatThrownBy(() -> service.retrieve(null, Map.of("k", "v"), "t1"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void retrieve_nullTenantIdThrows() {
        assertThatThrownBy(() -> service.retrieve(hvacConfig(), Map.of("k", "v"), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructor_nullStoreThrows() {
        assertThatThrownBy(() -> new IoTCbrRetrievalService(null))
                .isInstanceOf(NullPointerException.class);
    }
}
