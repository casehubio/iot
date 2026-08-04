package io.casehub.iot.webapp.app.rest;

import io.casehub.api.context.CaseContext;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.cbr.CbrConfig;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.engine.queue.model.CaseQueueEntry;
import io.casehub.engine.queue.model.QueueEntryStatus;
import io.casehub.engine.queue.service.CaseQueueService;
import io.casehub.engine.queue.spi.CaseQueueEntryStore;
import io.casehub.iot.webapp.cbr.IoTCbrRetrievalService;
import io.casehub.iot.webapp.cbr.ResolutionSuggestion;
import io.casehub.iot.webapp.resolution.AiEscalationContext;
import io.casehub.iot.webapp.resolution.ExecutedActionResult;
import io.casehub.iot.webapp.resolution.PlannedActionSpec;
import io.casehub.iot.webapp.resolution.QueueEntryDetail;
import io.casehub.iot.webapp.resolution.QueueEntrySummary;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.api.view.SubjectViewSpec;
import io.casehub.platform.api.view.SubjectViewStore;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ResolutionQueueResourceTest {

    private static final String TENANCY = "test-tenant";
    private static final UUID AI_VIEW_ID = UUID.randomUUID();
    private static final UUID OPERATOR_VIEW_ID = UUID.randomUUID();

    private CaseQueueService queueService;
    private CaseQueueEntryStore entryStore;
    private CaseInstanceCache caseCache;
    private CaseDefinitionRegistry definitionRegistry;
    private SubjectViewStore viewStore;
    private IoTCbrRetrievalService retrievalService;
    private CurrentPrincipal principal;
    private ResolutionQueueResource resource;

    @BeforeEach
    void setUp() throws Exception {
        queueService = mock(CaseQueueService.class);
        entryStore = mock(CaseQueueEntryStore.class);
        caseCache = mock(CaseInstanceCache.class);
        definitionRegistry = mock(CaseDefinitionRegistry.class);
        viewStore = mock(SubjectViewStore.class);
        retrievalService = mock(IoTCbrRetrievalService.class);
        principal = mock(CurrentPrincipal.class);

        when(principal.tenancyId()).thenReturn(TENANCY);
        when(viewStore.findByTenancy(TENANCY)).thenReturn(List.of(
            new SubjectViewSpec(AI_VIEW_ID, "iot-ai-resolution", TENANCY,
                "iot-triage:ai-resolution", null, "enqueuedAt", "ASC", null, Instant.now()),
            new SubjectViewSpec(OPERATOR_VIEW_ID, "iot-operator-assisted", TENANCY,
                "iot-triage:operator-assisted", null, "enqueuedAt", "ASC", null, Instant.now())
        ));

        resource = new ResolutionQueueResource();
        inject(resource, "queueService", queueService);
        inject(resource, "entryStore", entryStore);
        inject(resource, "caseCache", caseCache);
        inject(resource, "definitionRegistry", definitionRegistry);
        inject(resource, "viewStore", viewStore);
        inject(resource, "retrievalService", retrievalService);
        inject(resource, "principal", principal);

        resource.init();
    }

    @Test
    void list_bothViewsCombined_returnsEntriesFromBothViews() {
        UUID caseId1 = UUID.randomUUID();
        UUID caseId2 = UUID.randomUUID();
        CaseQueueEntry aiEntry = pendingEntry(caseId1, AI_VIEW_ID, "iot-ai-resolution");
        CaseQueueEntry opEntry = escalatedEntry(caseId2);

        when(queueService.findByView(AI_VIEW_ID, TENANCY)).thenReturn(List.of(aiEntry));
        when(queueService.findByView(OPERATOR_VIEW_ID, TENANCY)).thenReturn(List.of(opEntry));
        CaseInstance inst1 = caseInstance(caseId1, "hvac-anomaly", workingContext());
        CaseInstance inst2 = caseInstance(caseId2, "leak-detected", workingContext());
        when(caseCache.get(caseId1)).thenReturn(inst1);
        when(caseCache.get(caseId2)).thenReturn(inst2);

        List<QueueEntrySummary> result = resource.list(null, null);

        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(s -> "iot-ai-resolution".equals(s.viewName())));
        assertTrue(result.stream().anyMatch(s -> "iot-operator-assisted".equals(s.viewName())));
    }

    @Test
    void list_viewFilter_returnsOnlyMatchingView() {
        UUID caseId = UUID.randomUUID();
        CaseQueueEntry aiEntry = pendingEntry(caseId, AI_VIEW_ID, "iot-ai-resolution");

        when(queueService.findByView(AI_VIEW_ID, TENANCY)).thenReturn(List.of(aiEntry));
        CaseInstance inst = caseInstance(caseId, "hvac-anomaly", workingContext());
        when(caseCache.get(caseId)).thenReturn(inst);

        List<QueueEntrySummary> result = resource.list("ai-resolution", null);

        assertEquals(1, result.size());
        assertEquals("iot-ai-resolution", result.get(0).viewName());
        verify(queueService, never()).findByView(OPERATOR_VIEW_ID, TENANCY);
    }

    @Test
    void list_statusPending_usesFindPending() {
        UUID caseId = UUID.randomUUID();
        CaseQueueEntry entry = pendingEntry(caseId, AI_VIEW_ID, "iot-ai-resolution");

        when(queueService.findPending(AI_VIEW_ID, TENANCY)).thenReturn(List.of(entry));
        when(queueService.findPending(OPERATOR_VIEW_ID, TENANCY)).thenReturn(List.of());
        CaseInstance inst = caseInstance(caseId, "hvac-anomaly", workingContext());
        when(caseCache.get(caseId)).thenReturn(inst);

        List<QueueEntrySummary> result = resource.list(null, "PENDING");

        assertEquals(1, result.size());
        verify(queueService, never()).findByView(any(), any());
    }

    @Test
    void list_defaultFilter_excludesRevoked() {
        UUID caseId1 = UUID.randomUUID();
        UUID caseId2 = UUID.randomUUID();
        CaseQueueEntry pending = pendingEntry(caseId1, AI_VIEW_ID, "iot-ai-resolution");
        CaseQueueEntry revoked = new CaseQueueEntry(UUID.randomUUID(), caseId2, TENANCY,
            AI_VIEW_ID, "iot-ai-resolution", QueueEntryStatus.REVOKED, Instant.now());

        when(queueService.findByView(AI_VIEW_ID, TENANCY)).thenReturn(List.of(pending, revoked));
        when(queueService.findByView(OPERATOR_VIEW_ID, TENANCY)).thenReturn(List.of());
        CaseInstance inst = caseInstance(caseId1, "hvac-anomaly", workingContext());
        when(caseCache.get(caseId1)).thenReturn(inst);

        List<QueueEntrySummary> result = resource.list(null, null);

        assertEquals(1, result.size());
        assertEquals("PENDING", result.get(0).status());
    }

    @Test
    void list_enrichment_extractsDeviceFieldsFromWorkingContext() {
        UUID caseId = UUID.randomUUID();
        CaseQueueEntry entry = pendingEntry(caseId, AI_VIEW_ID, "iot-ai-resolution");
        Map<String, Object> working = Map.of(
            "deviceId", "sensor-042",
            "deviceClass", "TemperatureSensor",
            "roomType", "Bedroom",
            "situationId", "temp-spike-007"
        );

        when(queueService.findByView(AI_VIEW_ID, TENANCY)).thenReturn(List.of(entry));
        when(queueService.findByView(OPERATOR_VIEW_ID, TENANCY)).thenReturn(List.of());
        CaseInstance inst = caseInstance(caseId, "temp-anomaly", working);
        when(caseCache.get(caseId)).thenReturn(inst);

        List<QueueEntrySummary> result = resource.list(null, null);

        QueueEntrySummary summary = result.get(0);
        assertEquals("sensor-042", summary.deviceId());
        assertEquals("TemperatureSensor", summary.deviceClass());
        assertEquals("Bedroom", summary.roomType());
        assertEquals("temp-spike-007", summary.situationId());
        assertEquals("temp-anomaly", summary.caseType());
    }

    @Test
    void list_caseNotInCache_returnsEntryWithNullCaseFields() {
        UUID caseId = UUID.randomUUID();
        CaseQueueEntry entry = pendingEntry(caseId, AI_VIEW_ID, "iot-ai-resolution");

        when(queueService.findByView(AI_VIEW_ID, TENANCY)).thenReturn(List.of(entry));
        when(queueService.findByView(OPERATOR_VIEW_ID, TENANCY)).thenReturn(List.of());
        when(caseCache.get(caseId)).thenReturn(null);

        List<QueueEntrySummary> result = resource.list(null, null);

        assertEquals(1, result.size());
        QueueEntrySummary summary = result.get(0);
        assertNull(summary.caseType());
        assertNull(summary.deviceId());
        assertNull(summary.deviceClass());
    }

    @Test
    void list_viewNotConfigured_returnsEmptyList() throws Exception {
        when(viewStore.findByTenancy(TENANCY)).thenReturn(List.of());
        resource.init();

        List<QueueEntrySummary> result = resource.list(null, null);

        assertTrue(result.isEmpty());
    }

    @Test
    void list_escalatedEntryWithNullViewName_resolvesFromMapping() {
        UUID caseId = UUID.randomUUID();
        CaseQueueEntry entry = escalatedEntry(caseId);

        when(queueService.findByView(AI_VIEW_ID, TENANCY)).thenReturn(List.of());
        when(queueService.findByView(OPERATOR_VIEW_ID, TENANCY)).thenReturn(List.of(entry));
        CaseInstance inst = caseInstance(caseId, "hvac-anomaly", workingContext());
        when(caseCache.get(caseId)).thenReturn(inst);

        List<QueueEntrySummary> result = resource.list(null, null);

        assertEquals(1, result.size());
        assertEquals("iot-operator-assisted", result.get(0).viewName());
        assertEquals("iot-ai-resolution", result.get(0).previousViewName());
    }

    @Test
    void detail_happyPath_returnsFullEnrichment() {
        UUID caseId = UUID.randomUUID();
        CaseQueueEntry entry = claimedEntry(caseId, AI_VIEW_ID, "iot-ai-resolution");
        Map<String, Object> working = workingContext();
        CaseInstance instance = caseInstance(caseId, "hvac-anomaly", working);

        CaseDefinition def = mock(CaseDefinition.class);
        CbrConfig cbrConfig = CbrConfig.builder().domain("iot").caseType("hvac-anomaly")
            .featureExtractor(ctx -> Map.of()).build();
        when(def.getCbrConfig()).thenReturn(cbrConfig);

        List<ExecutedActionResult> execResults = List.of(
            new ExecutedActionResult(
                new PlannedActionSpec("SET_TEMPERATURE", "thermo-001",
                    Map.of("target", 22), "Reset temp"),
                true, "SUCCESS"));

        when(entryStore.findById(entry.getId())).thenReturn(Optional.of(entry));
        when(caseCache.get(caseId)).thenReturn(instance);
        when(definitionRegistry.findByName("hvac-anomaly")).thenReturn(Optional.of(def));
        when(retrievalService.retrieve(eq(cbrConfig), any(), eq(TENANCY)))
            .thenReturn(List.of(new ResolutionSuggestion(
                "past-case-1", 0.92, "HVAC overheat", "Lower temp",
                "RESOLVED", 0.85, Map.of(), Map.of(), List.of())));
        when(instance.getCaseContext().get("aiResolutionResults")).thenReturn(execResults);
        when(instance.getCaseContext().get("aiEscalationContext")).thenReturn(null);

        QueueEntryDetail detail = resource.detail(entry.getId());

        assertNotNull(detail);
        assertEquals("thermo-001", detail.entry().deviceId());
        assertEquals(1, detail.suggestions().size());
        assertEquals(0.92, detail.suggestions().get(0).similarityScore());
        assertEquals(1, detail.executionResults().size());
        assertTrue(detail.executionResults().get(0).succeeded());
    }

    @Test
    void detail_entryNotFound_throws404() {
        UUID entryId = UUID.randomUUID();
        when(entryStore.findById(entryId)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> resource.detail(entryId));
    }

    @Test
    void detail_tenancyMismatch_throws404() {
        UUID caseId = UUID.randomUUID();
        CaseQueueEntry entry = new CaseQueueEntry(UUID.randomUUID(), caseId,
            "other-tenant", AI_VIEW_ID, "iot-ai-resolution",
            QueueEntryStatus.PENDING, Instant.now());

        when(entryStore.findById(entry.getId())).thenReturn(Optional.of(entry));

        assertThrows(NotFoundException.class, () -> resource.detail(entry.getId()));
    }

    @Test
    void detail_noAiState_returnsNullResolutionFields() {
        UUID caseId = UUID.randomUUID();
        CaseQueueEntry entry = pendingEntry(caseId, AI_VIEW_ID, "iot-ai-resolution");
        CaseInstance instance = caseInstance(caseId, "hvac-anomaly", workingContext());

        when(entryStore.findById(entry.getId())).thenReturn(Optional.of(entry));
        when(caseCache.get(caseId)).thenReturn(instance);
        when(definitionRegistry.findByName("hvac-anomaly")).thenReturn(Optional.empty());

        QueueEntryDetail detail = resource.detail(entry.getId());

        assertNull(detail.escalationContext());
        assertTrue(detail.suggestions().isEmpty());
        assertTrue(detail.executionResults().isEmpty());
    }

    @Test
    void detail_noCbrConfig_returnsEmptySuggestions() {
        UUID caseId = UUID.randomUUID();
        CaseQueueEntry entry = pendingEntry(caseId, AI_VIEW_ID, "iot-ai-resolution");
        CaseInstance instance = caseInstance(caseId, "hvac-anomaly", workingContext());

        CaseDefinition def = mock(CaseDefinition.class);
        when(def.getCbrConfig()).thenReturn(null);

        when(entryStore.findById(entry.getId())).thenReturn(Optional.of(entry));
        when(caseCache.get(caseId)).thenReturn(instance);
        when(definitionRegistry.findByName("hvac-anomaly")).thenReturn(Optional.of(def));

        QueueEntryDetail detail = resource.detail(entry.getId());

        assertTrue(detail.suggestions().isEmpty());
        verifyNoInteractions(retrievalService);
    }

    @Test
    void detail_escalatedEntry_hasEscalationContext() {
        UUID caseId = UUID.randomUUID();
        CaseQueueEntry entry = escalatedEntry(caseId);
        CaseInstance instance = caseInstance(caseId, "hvac-anomaly", workingContext());

        AiEscalationContext escalation = new AiEscalationContext(
            "Risk gate: LOCK requires approval", List.of(), "Analysis text",
            List.of(new PlannedActionSpec("LOCK", "lock-001", Map.of(), "Secure")),
            null);

        when(entryStore.findById(entry.getId())).thenReturn(Optional.of(entry));
        when(caseCache.get(caseId)).thenReturn(instance);
        when(definitionRegistry.findByName("hvac-anomaly")).thenReturn(Optional.empty());
        when(instance.getCaseContext().get("aiEscalationContext")).thenReturn(escalation);

        QueueEntryDetail detail = resource.detail(entry.getId());

        assertEquals("iot-operator-assisted", detail.entry().viewName());
        assertEquals("iot-ai-resolution", detail.entry().previousViewName());
        assertNotNull(detail.escalationContext());
        assertEquals("Risk gate: LOCK requires approval", detail.escalationContext().reason());
    }

    // --- helpers ---

    private CaseQueueEntry pendingEntry(UUID caseId, UUID viewId, String viewName) {
        return new CaseQueueEntry(UUID.randomUUID(), caseId, TENANCY,
            viewId, viewName, QueueEntryStatus.PENDING, Instant.now());
    }

    private CaseQueueEntry claimedEntry(UUID caseId, UUID viewId, String viewName) {
        CaseQueueEntry e = new CaseQueueEntry(UUID.randomUUID(), caseId, TENANCY,
            viewId, viewName, QueueEntryStatus.CLAIMED, Instant.now());
        e.setAssignedTo("iot-ai-agent");
        e.setClaimedAt(Instant.now());
        return e;
    }

    private CaseQueueEntry escalatedEntry(UUID caseId) {
        CaseQueueEntry e = new CaseQueueEntry(UUID.randomUUID(), caseId, TENANCY,
            OPERATOR_VIEW_ID, null, QueueEntryStatus.PENDING, Instant.now());
        e.setPreviousViewId(AI_VIEW_ID);
        e.setPreviousViewName("iot-ai-resolution");
        e.setEscalatedAt(Instant.now());
        return e;
    }

    private CaseInstance caseInstance(UUID caseId, String caseType,
                                      Map<String, Object> working) {
        CaseInstance instance = new CaseInstance();
        instance.setUuid(caseId);
        CaseMetaModel meta = new CaseMetaModel();
        meta.setName(caseType);
        instance.setCaseMetaModel(meta);
        CaseContext ctx = mock(CaseContext.class);
        doReturn(working).when(ctx).getOrDefault(eq("working"), any());
        instance.setCaseContext(ctx);
        return instance;
    }

    private Map<String, Object> workingContext() {
        return Map.of(
            "deviceId", "thermo-001",
            "deviceClass", "Thermostat",
            "roomType", "LivingRoom",
            "situationId", "hvac-anomaly-001"
        );
    }

    private static void inject(Object target, String fieldName, Object value)
            throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }
}
