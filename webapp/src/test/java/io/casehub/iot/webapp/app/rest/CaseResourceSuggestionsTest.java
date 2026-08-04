package io.casehub.iot.webapp.app.rest;

import io.casehub.api.context.CaseContext;
import io.casehub.api.context.ReadableLayer;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.cbr.CbrConfig;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.iot.webapp.cbr.IoTCbrRetrievalService;
import io.casehub.iot.webapp.cbr.ResolutionSuggestion;
import io.casehub.platform.api.identity.CurrentPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CaseResourceSuggestionsTest {

    private CaseInstanceCache cache;
    private CaseDefinitionRegistry registry;
    private IoTCbrRetrievalService retrievalService;
    private CurrentPrincipal principal;
    private CaseResource resource;

    @BeforeEach
    void setUp() {
        cache = mock(CaseInstanceCache.class);
        registry = mock(CaseDefinitionRegistry.class);
        retrievalService = mock(IoTCbrRetrievalService.class);
        principal = mock(CurrentPrincipal.class);
        when(principal.tenancyId()).thenReturn("test-tenant");

        resource = new CaseResource();
        resource.principal = principal;
        resource.caseInstanceCache = cache;
        resource.caseDefinitionRegistry = registry;
        resource.retrievalService = retrievalService;
    }

    @Test
    void getSuggestions_returnsSuggestionsForCase() {
        var caseId = UUID.randomUUID();
        var instance = mockCaseInstance(caseId, "hvac-anomaly",
                Map.of("deviceClass", "thermostat", "roomType", "bedroom"));
        when(cache.get(caseId)).thenReturn(instance);

        var definition = mockDefinitionWithCbrConfig("hvac-anomaly");
        when(registry.findByName("hvac-anomaly")).thenReturn(Optional.of(definition));

        var suggestion = new ResolutionSuggestion(
                "past-1", 0.87, "Temp spike", "Filter replaced", "RESOLVED",
                0.95, Map.of(), Map.of(), List.of());
        when(retrievalService.retrieve(any(), any(), eq("test-tenant")))
                .thenReturn(List.of(suggestion));

        var response = resource.getSuggestions(caseId);

        assertThat(response.caseId()).isEqualTo(caseId);
        assertThat(response.caseType()).isEqualTo("hvac-anomaly");
        assertThat(response.suggestionCount()).isEqualTo(1);
        assertThat(response.suggestions()).hasSize(1);
        assertThat(response.suggestions().getFirst().similarityScore()).isEqualTo(0.87);
    }

    @Test
    void getSuggestions_caseNotFound_throws404() {
        when(cache.get(any())).thenReturn(null);
        assertThatThrownBy(() -> resource.getSuggestions(UUID.randomUUID()))
                .isInstanceOf(jakarta.ws.rs.NotFoundException.class);
    }

    @Test
    void getSuggestions_noCbrConfig_returnsEmptySuggestions() {
        var caseId = UUID.randomUUID();
        var instance = mockCaseInstance(caseId, "unknown-type", Map.of());
        when(cache.get(caseId)).thenReturn(instance);

        var definition = mock(CaseDefinition.class);
        when(definition.getCbrConfig()).thenReturn(null);
        when(registry.findByName("unknown-type")).thenReturn(Optional.of(definition));

        var response = resource.getSuggestions(caseId);
        assertThat(response.suggestions()).isEmpty();
        assertThat(response.suggestionCount()).isZero();
    }

    @Test
    void getSuggestions_definitionNotFound_returnsEmptySuggestions() {
        var caseId = UUID.randomUUID();
        var instance = mockCaseInstance(caseId, "missing-type", Map.of());
        when(cache.get(caseId)).thenReturn(instance);
        when(registry.findByName("missing-type")).thenReturn(Optional.empty());

        var response = resource.getSuggestions(caseId);
        assertThat(response.suggestions()).isEmpty();
    }

    @Test
    void getSuggestions_multipleSuggestions_allReturned() {
        var caseId = UUID.randomUUID();
        var instance = mockCaseInstance(caseId, "hvac-anomaly",
                Map.of("deviceClass", "thermostat"));
        when(cache.get(caseId)).thenReturn(instance);

        var definition = mockDefinitionWithCbrConfig("hvac-anomaly");
        when(registry.findByName("hvac-anomaly")).thenReturn(Optional.of(definition));

        var s1 = new ResolutionSuggestion("p1", 0.9, "prob1", "sol1", "R", null, Map.of(), Map.of(), List.of());
        var s2 = new ResolutionSuggestion("p2", 0.7, "prob2", "sol2", "R", null, Map.of(), Map.of(), List.of());
        when(retrievalService.retrieve(any(), any(), eq("test-tenant")))
                .thenReturn(List.of(s1, s2));

        var response = resource.getSuggestions(caseId);
        assertThat(response.suggestionCount()).isEqualTo(2);
    }

    @Test
    void acceptSuggestion_caseNotFound_throws404() {
        when(cache.get(any())).thenReturn(null);
        assertThatThrownBy(() -> resource.acceptSuggestion(UUID.randomUUID(), "past-1"))
                .isInstanceOf(jakarta.ws.rs.NotFoundException.class);
    }

    @Test
    void acceptSuggestion_suggestionNotFound_throws404() {
        var caseId = UUID.randomUUID();
        var instance = mockCaseInstance(caseId, "hvac-anomaly", Map.of("deviceClass", "thermostat"));
        when(cache.get(caseId)).thenReturn(instance);

        var definition = mockDefinitionWithCbrConfig("hvac-anomaly");
        when(registry.findByName("hvac-anomaly")).thenReturn(Optional.of(definition));
        when(retrievalService.retrieve(any(), any(), eq("test-tenant")))
                .thenReturn(List.of());

        assertThatThrownBy(() -> resource.acceptSuggestion(caseId, "nonexistent"))
                .isInstanceOf(jakarta.ws.rs.NotFoundException.class)
                .hasMessageContaining("nonexistent");
    }

    @Test
    void acceptSuggestion_copiesPlanStepsToContext() {
        var caseId = UUID.randomUUID();
        var ctx    = mock(CaseContext.class);
        when(ctx.getOrDefault(eq("acceptedSuggestions"), any()))
                .thenReturn(new java.util.HashSet<String>());
        when(ctx.set(any(String.class), any())).thenReturn(ctx);

        var instance = mockCaseInstanceWithContext(caseId, "hvac-anomaly",
                                                   Map.of("deviceClass", "thermostat"), ctx);
        when(cache.get(caseId)).thenReturn(instance);

        var definition = mockDefinitionWithCbrConfig("hvac-anomaly");
        when(registry.findByName("hvac-anomaly")).thenReturn(Optional.of(definition));

        var planTrace = new io.casehub.neocortex.memory.cbr.PlanTrace(
                "bind-1", "device-control", "set-temp", "SUCCESS", 1, Map.of(), null);
        var suggestion = new ResolutionSuggestion(
                "past-1", 0.87, "Temp spike", "Filter replaced", "RESOLVED",
                0.95, Map.of(), Map.of(), List.of(planTrace));
        when(retrievalService.retrieve(any(), any(), eq("test-tenant")))
                .thenReturn(List.of(suggestion));

        resource.acceptSuggestion(caseId, "past-1");

        var planCaptor = org.mockito.ArgumentCaptor.forClass(Object.class);
        org.mockito.Mockito.verify(ctx).set(eq("suggestedPlan"), planCaptor.capture());
        assertThat(planCaptor.getValue()).isInstanceOf(List.class);

        var acceptedCaptor = org.mockito.ArgumentCaptor.forClass(Object.class);
        org.mockito.Mockito.verify(ctx).set(eq("acceptedSuggestions"), acceptedCaptor.capture());
        assertThat(acceptedCaptor.getValue()).isInstanceOf(java.util.Set.class);
        @SuppressWarnings("unchecked")
        var acceptedSet = (java.util.Set<String>) acceptedCaptor.getValue();
        assertThat(acceptedSet).contains("past-1");
    }

    @Test
    void acceptSuggestion_idempotent_secondCallIsNoOp() {
        var caseId          = UUID.randomUUID();
        var alreadyAccepted = new java.util.HashSet<String>();
        alreadyAccepted.add("past-1");

        var ctx = mock(CaseContext.class);
        when(ctx.getOrDefault(eq("acceptedSuggestions"), any()))
                .thenReturn(alreadyAccepted);

        var instance = mockCaseInstanceWithContext(caseId, "hvac-anomaly",
                                                   Map.of("deviceClass", "thermostat"), ctx);
        when(cache.get(caseId)).thenReturn(instance);

        resource.acceptSuggestion(caseId, "past-1");

        org.mockito.Mockito.verify(ctx, org.mockito.Mockito.never()).set(eq("suggestedPlan"), any());
        org.mockito.Mockito.verify(ctx, org.mockito.Mockito.never()).set(eq("acceptedSuggestions"), any());
    }

    private CaseInstance mockCaseInstanceWithContext(UUID id, String caseType,
                                                     Map<String, Object> workingData, CaseContext ctx) {
        var meta = new CaseMetaModel();
        meta.setName(caseType);

        var layer = mock(ReadableLayer.class);
        for (var entry : workingData.entrySet()) {
            when(layer.get(entry.getKey())).thenReturn(entry.getValue());
        }
        when(ctx.layer("working")).thenReturn(layer);

        var instance = mock(CaseInstance.class);
        when(instance.getUuid()).thenReturn(id);
        when(instance.getCaseMetaModel()).thenReturn(meta);
        when(instance.getCaseContext()).thenReturn(ctx);
        instance.tenancyId = "test-tenant";

        return instance;
    }


    private CaseInstance mockCaseInstance(UUID id, String caseType, Map<String, Object> workingData) {
        var meta = new CaseMetaModel();
        meta.setName(caseType);

        var layer = mock(ReadableLayer.class);
        for (var entry : workingData.entrySet()) {
            when(layer.get(entry.getKey())).thenReturn(entry.getValue());
        }

        var ctx = mock(CaseContext.class);
        when(ctx.layer("working")).thenReturn(layer);
        when(ctx.getOrDefault(eq("acceptedSuggestions"), any()))
                .thenReturn(new java.util.HashSet<String>());

        var instance = mock(CaseInstance.class);
        when(instance.getUuid()).thenReturn(id);
        when(instance.getCaseMetaModel()).thenReturn(meta);
        when(instance.getCaseContext()).thenReturn(ctx);
        instance.tenancyId = "test-tenant";

        return instance;
    }

    private CaseDefinition mockDefinitionWithCbrConfig(String caseType) {
        var config = CbrConfig.builder()
                .domain("iot")
                .caseType(caseType)
                .featureExtractor(ctx -> {
                    var working = ctx.layer("working");
                    var features = new LinkedHashMap<String, Object>();
                    var dc = working.get("deviceClass");
                    if (dc != null) features.put("deviceClass", dc);
                    var rt = working.get("roomType");
                    if (rt != null) features.put("roomType", rt);
                    return Map.copyOf(features);
                })
                .weight("deviceClass", 2.0)
                .weight("roomType", 1.5)
                .topK(5)
                .minSimilarity(0.3)
                .vectorWeight(0.0)
                .build();

        var definition = mock(CaseDefinition.class);
        when(definition.getCbrConfig()).thenReturn(config);
        when(definition.getName()).thenReturn(caseType);

        return definition;
    }
}
