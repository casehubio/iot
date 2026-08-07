package io.casehub.iot.webapp.app.resolution;

import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.cbr.CbrConfig;
import io.casehub.api.context.CaseContext;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.engine.common.spi.event.CaseContextUpdatedEvent;
import io.casehub.engine.queue.model.CaseQueueEntry;
import io.casehub.engine.queue.model.QueueEntryStatus;
import io.casehub.engine.queue.service.CaseQueueService;
import io.casehub.iot.webapp.app.triage.IoTTriageConfig;
import io.casehub.iot.webapp.cbr.IoTCbrRetrievalService;
import io.casehub.iot.webapp.cbr.ResolutionSuggestion;
import io.casehub.platform.api.view.SubjectViewSpec;
import io.casehub.platform.api.view.SubjectViewStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IoTCbrReEvaluationObserverTest {

    private static final String TENANCY = "test-tenant";
    private static final UUID AI_VIEW_ID = UUID.randomUUID();
    private static final UUID OPERATOR_ASSISTED_VIEW_ID = UUID.randomUUID();
    private static final UUID OPERATOR_MANUAL_VIEW_ID = UUID.randomUUID();

    private CaseQueueService queueService;
    private CaseInstanceCache caseCache;
    private IoTCbrRetrievalService retrievalService;
    private CaseDefinitionRegistry definitionRegistry;
    private SubjectViewStore viewStore;
    private IoTTriageConfig triageConfig;
    private SimpleMeterRegistry meterRegistry;
    private IoTCbrReEvaluationObserver observer;

    @BeforeEach
    void setUp() throws Exception {
        queueService = mock(CaseQueueService.class);
        caseCache = mock(CaseInstanceCache.class);
        retrievalService = mock(IoTCbrRetrievalService.class);
        definitionRegistry = mock(CaseDefinitionRegistry.class);
        viewStore = mock(SubjectViewStore.class);
        triageConfig = mock(IoTTriageConfig.class);
        meterRegistry = new SimpleMeterRegistry();

        when(triageConfig.aiMinSimilarity()).thenReturn(0.85);
        when(triageConfig.aiMinConsistency()).thenReturn(0.80);

        when(viewStore.findByTenancy(TENANCY)).thenReturn(List.of(
            new SubjectViewSpec(AI_VIEW_ID, "iot-ai-resolution", TENANCY,
                "iot-triage:ai-resolution", null, "enqueuedAt", "ASC", null, Instant.now()),
            new SubjectViewSpec(OPERATOR_ASSISTED_VIEW_ID, "iot-operator-assisted", TENANCY,
                "iot-triage:operator-assisted", null, "enqueuedAt", "ASC", null, Instant.now()),
            new SubjectViewSpec(OPERATOR_MANUAL_VIEW_ID, "iot-operator-manual", TENANCY,
                "iot-triage:operator-manual", null, "enqueuedAt", "ASC", null, Instant.now())
        ));

        observer = new IoTCbrReEvaluationObserver();
        inject(observer, "queueService", queueService);
        inject(observer, "caseCache", caseCache);
        inject(observer, "retrievalService", retrievalService);
        inject(observer, "definitionRegistry", definitionRegistry);
        inject(observer, "viewStore", viewStore);
        inject(observer, "triageConfig", triageConfig);
        inject(observer, "tenancyId", TENANCY);
        inject(observer, "registry", meterRegistry);
        observer.init();
    }

    @Test
    void bandDropHighToMedium_escalatesToOperatorAssisted() {
        UUID caseId = UUID.randomUUID();
        CaseInstance instance = caseInstance(caseId, "hvac-anomaly", 0.90);
        CaseQueueEntry entry = aiQueueEntry(caseId, QueueEntryStatus.PENDING);
        setupStandardMocks(caseId, instance, entry);

        when(retrievalService.retrieve(any(), any(), eq(TENANCY)))
            .thenReturn(List.of(suggestion(0.65, "RESOLVED")));

        observer.onContextUpdated(new CaseContextUpdatedEvent(caseId, "working", TENANCY));

        verify(queueService).escalate(entry.getId(), TENANCY, OPERATOR_ASSISTED_VIEW_ID);
        assertEquals(1.0, counterValue("casehub.iot.ai.resolution.reevaluation.rerouted",
            "from.band", "high", "to.band", "medium", "target.view", "iot-operator-assisted"));
    }

    @Test
    void bandDropHighToLow_escalatesToOperatorManual() {
        UUID caseId = UUID.randomUUID();
        CaseInstance instance = caseInstance(caseId, "hvac-anomaly", 0.90);
        CaseQueueEntry entry = aiQueueEntry(caseId, QueueEntryStatus.PENDING);
        setupStandardMocks(caseId, instance, entry);

        when(retrievalService.retrieve(any(), any(), eq(TENANCY)))
            .thenReturn(List.of(suggestion(0.30, "RESOLVED")));

        observer.onContextUpdated(new CaseContextUpdatedEvent(caseId, "working", TENANCY));

        verify(queueService).escalate(entry.getId(), TENANCY, OPERATOR_MANUAL_VIEW_ID);
        assertEquals(1.0, counterValue("casehub.iot.ai.resolution.reevaluation.rerouted",
            "from.band", "high", "to.band", "low", "target.view", "iot-operator-manual"));
    }

    @Test
    void bandUnchanged_noEscalation() {
        UUID caseId = UUID.randomUUID();
        CaseInstance instance = caseInstance(caseId, "hvac-anomaly", 0.90);
        CaseQueueEntry entry = aiQueueEntry(caseId, QueueEntryStatus.PENDING);
        setupStandardMocks(caseId, instance, entry);

        when(retrievalService.retrieve(any(), any(), eq(TENANCY)))
            .thenReturn(List.of(
                suggestion(0.92, "RESOLVED"),
                suggestion(0.88, "RESOLVED")));

        observer.onContextUpdated(new CaseContextUpdatedEvent(caseId, "working", TENANCY));

        verify(queueService, never()).escalate(any(), any(), any());
        assertEquals(1.0, counterValue("casehub.iot.ai.resolution.reevaluation.checked"));
        assertEquals(0.0, counterValue("casehub.iot.ai.resolution.reevaluation.rerouted",
            "from.band", "high", "to.band", "medium", "target.view", "iot-operator-assisted"));
    }

    @Test
    void nonWorkingLayerChange_filteredOut() {
        UUID caseId = UUID.randomUUID();

        observer.onContextUpdated(new CaseContextUpdatedEvent(caseId, "episodic", TENANCY));

        verify(queueService, never()).findByView(any(), any());
        verify(caseCache, never()).get(any());
    }

    @Test
    void caseNotInAiQueue_filteredOut() {
        UUID caseId = UUID.randomUUID();
        when(queueService.findByView(AI_VIEW_ID, TENANCY)).thenReturn(List.of());

        observer.onContextUpdated(new CaseContextUpdatedEvent(caseId, "working", TENANCY));

        verify(caseCache, never()).get(any());
        verify(retrievalService, never()).retrieve(any(), any(), any());
    }

    @Test
    void debounce_coalesces_rapidEvents() {
        UUID caseId = UUID.randomUUID();
        CaseInstance instance = caseInstance(caseId, "hvac-anomaly", 0.90);
        CaseQueueEntry entry = aiQueueEntry(caseId, QueueEntryStatus.PENDING);
        setupStandardMocks(caseId, instance, entry);

        when(retrievalService.retrieve(any(), any(), eq(TENANCY)))
            .thenReturn(List.of(suggestion(0.92, "RESOLVED")));

        observer.onContextUpdated(new CaseContextUpdatedEvent(caseId, "working", TENANCY));
        observer.onContextUpdated(new CaseContextUpdatedEvent(caseId, "working", TENANCY));

        verify(retrievalService, times(1)).retrieve(any(), any(), eq(TENANCY));
        assertEquals(1.0, counterValue("casehub.iot.ai.resolution.reevaluation.debounced"));
    }

    @Test
    void pendingEntry_rerouted() {
        UUID caseId = UUID.randomUUID();
        CaseInstance instance = caseInstance(caseId, "hvac-anomaly", 0.90);
        CaseQueueEntry entry = aiQueueEntry(caseId, QueueEntryStatus.PENDING);
        setupStandardMocks(caseId, instance, entry);

        when(retrievalService.retrieve(any(), any(), eq(TENANCY)))
            .thenReturn(List.of(suggestion(0.65, "RESOLVED")));

        observer.onContextUpdated(new CaseContextUpdatedEvent(caseId, "working", TENANCY));

        verify(queueService).escalate(entry.getId(), TENANCY, OPERATOR_ASSISTED_VIEW_ID);
    }

    @Test
    void claimedEntry_rerouted() {
        UUID caseId = UUID.randomUUID();
        CaseInstance instance = caseInstance(caseId, "hvac-anomaly", 0.90);
        CaseQueueEntry entry = aiQueueEntry(caseId, QueueEntryStatus.CLAIMED);
        setupStandardMocks(caseId, instance, entry);

        when(retrievalService.retrieve(any(), any(), eq(TENANCY)))
            .thenReturn(List.of(suggestion(0.65, "RESOLVED")));

        observer.onContextUpdated(new CaseContextUpdatedEvent(caseId, "working", TENANCY));

        verify(queueService).escalate(entry.getId(), TENANCY, OPERATOR_ASSISTED_VIEW_ID);
    }

    @Test
    void viewsNotResolved_noErrors() throws Exception {
        IoTCbrReEvaluationObserver disabledObserver = new IoTCbrReEvaluationObserver();
        SubjectViewStore emptyStore = mock(SubjectViewStore.class);
        when(emptyStore.findByTenancy(TENANCY)).thenReturn(List.of());

        inject(disabledObserver, "queueService", queueService);
        inject(disabledObserver, "caseCache", caseCache);
        inject(disabledObserver, "retrievalService", retrievalService);
        inject(disabledObserver, "definitionRegistry", definitionRegistry);
        inject(disabledObserver, "viewStore", emptyStore);
        inject(disabledObserver, "triageConfig", triageConfig);
        inject(disabledObserver, "tenancyId", TENANCY);
        inject(disabledObserver, "registry", meterRegistry);
        disabledObserver.init();

        UUID caseId = UUID.randomUUID();
        disabledObserver.onContextUpdated(new CaseContextUpdatedEvent(caseId, "working", TENANCY));

        verify(queueService, never()).findByView(any(), any());
    }

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    private CaseInstance caseInstance(UUID caseId, String caseType, double cbrBestSimilarity) {
        CaseInstance instance = new CaseInstance();
        instance.setUuid(caseId);
        CaseMetaModel meta = new CaseMetaModel();
        meta.setName(caseType);
        instance.setCaseMetaModel(meta);
        CaseContext ctx = mock(CaseContext.class);
        when(ctx.getOrDefault(any(), any())).thenReturn(Map.of(
            "deviceClass", "thermostat",
            "roomType", "living_room",
            "cbrBestSimilarity", cbrBestSimilarity,
            "cbrOutcomeConsistency", 0.9,
            "cbrMatchCount", 3
        ));
        instance.setCaseContext(ctx);
        return instance;
    }

    private CaseQueueEntry aiQueueEntry(UUID caseId, QueueEntryStatus status) {
        CaseQueueEntry entry = new CaseQueueEntry(UUID.randomUUID(), caseId, TENANCY,
            AI_VIEW_ID, "iot-ai-resolution", status, Instant.now());
        if (status == QueueEntryStatus.CLAIMED) {
            entry.setAssignedTo("iot-ai-agent");
            entry.setClaimedAt(Instant.now());
        }
        return entry;
    }

    private void setupStandardMocks(UUID caseId, CaseInstance instance, CaseQueueEntry entry) {
        CaseDefinition def = mock(CaseDefinition.class);
        when(def.getCbrConfig()).thenReturn(CbrConfig.builder()
            .domain("iot").caseType("hvac-anomaly")
            .featureExtractor(ctx -> Map.of()).build());
        when(caseCache.get(caseId)).thenReturn(instance);
        when(definitionRegistry.findByName("hvac-anomaly")).thenReturn(Optional.of(def));
        when(queueService.findByView(AI_VIEW_ID, TENANCY)).thenReturn(List.of(entry));
    }

    private ResolutionSuggestion suggestion(double similarity, String outcome) {
        return new ResolutionSuggestion(
            UUID.randomUUID().toString(), similarity,
            "Test problem", "Test solution", outcome, 0.9,
            Map.of(), Map.of(), List.of());
    }

    private double counterValue(String name, String... tags) {
        var counter = meterRegistry.find(name).tags(tags).counter();
        return counter != null ? counter.count() : 0.0;
    }
}
