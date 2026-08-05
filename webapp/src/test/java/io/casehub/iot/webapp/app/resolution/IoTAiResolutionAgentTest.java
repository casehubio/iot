package io.casehub.iot.webapp.app.resolution;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.context.CaseContext;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ai.Agent;
import io.casehub.api.model.ai.AgentException;
import io.casehub.api.model.cbr.CbrConfig;
import io.casehub.api.spi.ActionRiskClassifier;
import io.casehub.api.spi.RiskDecision;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.internal.model.CaseMetaModel;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.engine.queue.model.CaseQueueEntry;
import io.casehub.engine.queue.model.QueueEntryStatus;
import io.casehub.engine.queue.service.CaseQueueService;
import io.casehub.iot.webapp.cbr.IoTCbrRetrievalService;
import io.casehub.platform.api.view.SubjectViewSpec;
import io.casehub.platform.api.view.SubjectViewStore;
import io.casehub.worker.api.WorkerResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.ConnectException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class IoTAiResolutionAgentTest {

    private static final String TENANCY = "test-tenant";
    private static final UUID AI_VIEW_ID = UUID.randomUUID();
    private static final UUID OPERATOR_VIEW_ID = UUID.randomUUID();

    private CaseQueueService queueService;

    private CaseInstanceCache caseCache;
    private IoTCbrRetrievalService retrievalService;
    private ActionRiskClassifier riskClassifier;
    private CaseDefinitionRegistry definitionRegistry;
    private SubjectViewStore viewStore;
    private Agent llmAgent;
    @SuppressWarnings("unchecked")
    private Function<Map<String, Object>, WorkerResult<Map<String, Object>>> deviceCommandFn =
            mock(Function.class);
    private IoTAiResolutionAgent agent;
    private SimpleMeterRegistry  meterRegistry;


    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        queueService = mock(CaseQueueService.class);

        caseCache = mock(CaseInstanceCache.class);
        retrievalService = mock(IoTCbrRetrievalService.class);
        riskClassifier = mock(ActionRiskClassifier.class);
        definitionRegistry = mock(CaseDefinitionRegistry.class);
        viewStore = mock(SubjectViewStore.class);
        llmAgent = mock(Agent.class);
        deviceCommandFn = mock(Function.class);

        IoTAiResolutionConfig config = mock(IoTAiResolutionConfig.class);
        when(config.enabled()).thenReturn(true);
        when(config.timeoutSeconds()).thenReturn(300);
        when(config.agentId()).thenReturn("iot-ai-agent");
        when(config.maxConcurrentLlmCalls()).thenReturn(3);

        when(viewStore.findByTenancy(TENANCY)).thenReturn(List.of(
            new SubjectViewSpec(AI_VIEW_ID, "iot-ai-resolution", TENANCY,
                "iot-triage:ai-resolution", null, "enqueuedAt", "ASC", null, Instant.now()),
            new SubjectViewSpec(OPERATOR_VIEW_ID, "iot-operator-assisted", TENANCY,
                "iot-triage:operator-assisted", null, "enqueuedAt", "ASC", null, Instant.now())
        ));

        ExecutorService directExecutor = mock(ExecutorService.class);
        when(directExecutor.submit(any(Runnable.class))).thenAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        });

        agent = new IoTAiResolutionAgent();
        inject(agent, "queueService", queueService);

        inject(agent, "caseCache", caseCache);
        inject(agent, "retrievalService", retrievalService);
        inject(agent, "riskClassifier", riskClassifier);
        inject(agent, "definitionRegistry", definitionRegistry);
        inject(agent, "viewStore", viewStore);
        inject(agent, "config", config);
        inject(agent, "tenancyId", TENANCY);
        inject(agent, "llmAgent", llmAgent);
        inject(agent, "deviceCommandFn", deviceCommandFn);
        inject(agent, "objectMapper", new ObjectMapper());
        inject(agent, "virtualThreads", directExecutor);
        meterRegistry = new SimpleMeterRegistry();
        inject(agent, "registry", meterRegistry);

        agent.init();
    }

    @Test
    void happyPath_claimsExecutesAndWritesResults() {
        UUID caseId = UUID.randomUUID();
        CaseQueueEntry entry = pendingEntry(caseId);
        CaseInstance instance = caseInstance(caseId, "hvac-anomaly");

        CaseDefinition def = mock(CaseDefinition.class);
        when(def.getCbrConfig()).thenReturn(CbrConfig.builder().domain("iot").caseType("hvac-anomaly").featureExtractor(ctx -> Map.of()).build());

        when(queueService.findPending(AI_VIEW_ID, TENANCY)).thenReturn(List.of(entry));
        when(queueService.claim(entry.getId(), TENANCY, "iot-ai-agent")).thenAnswer(inv -> {
            entry.setStatus(QueueEntryStatus.CLAIMED);
            entry.setAssignedTo("iot-ai-agent");
            entry.setClaimedAt(Instant.now());
            return entry;
        });
        when(caseCache.get(caseId)).thenReturn(instance);
        when(definitionRegistry.findByName("hvac-anomaly")).thenReturn(Optional.of(def));
        when(retrievalService.retrieve(any(), any(), eq(TENANCY))).thenReturn(List.of());
        when(llmAgent.execute(any())).thenReturn(llmExecuteResult());
        when(riskClassifier.classify(any(), any())).thenReturn(new RiskDecision.Autonomous());
        when(deviceCommandFn.apply(any())).thenReturn(WorkerResult.of(Map.of("result", "SUCCESS")));
        when(queueService.findByView(AI_VIEW_ID, TENANCY)).thenReturn(List.of(entry));

        agent.poll();

        verify(queueService).claim(entry.getId(), TENANCY, "iot-ai-agent");
        verify(llmAgent).execute(any());
        verify(deviceCommandFn).apply(any());
        verify(instance.getCaseContext()).set(eq("aiResolutionResults"), any());

        assertThat(counterValue("casehub.iot.ai.resolution.entries.processed",
                "outcome", "executed", "cbr.band", "none")).isEqualTo(1.0);
        assertThat(counterValue("casehub.iot.ai.resolution.actions.executed",
                "succeeded", "true")).isGreaterThanOrEqualTo(1.0);
        assertThat(timerCount("casehub.iot.ai.resolution.llm.call.duration",
                "outcome", "success")).isEqualTo(1);
        assertThat(timerCount("casehub.iot.ai.resolution.poll.duration")).isEqualTo(1);
    }

    @Test
    void llmDecidesToEscalate_movesEntryToOperatorAssisted() {
        UUID caseId = UUID.randomUUID();
        CaseQueueEntry entry = pendingEntry(caseId);
        CaseInstance instance = caseInstance(caseId, "hvac-anomaly");

        setupStandardMocks(entry, instance);
        when(llmAgent.execute(any())).thenReturn(llmEscalateResult());
        when(queueService.findByView(AI_VIEW_ID, TENANCY)).thenReturn(List.of());

        agent.poll();

        verify(queueService).escalate(entry.getId(), TENANCY, OPERATOR_VIEW_ID);
        verify(deviceCommandFn, never()).apply(any());

        assertThat(counterValue("casehub.iot.ai.resolution.entries.processed",
                "outcome", "llm-escalated", "cbr.band", "none")).isEqualTo(1.0);
    }

    @Test
    void riskGateTriggersEscalation() {
        UUID caseId = UUID.randomUUID();
        CaseQueueEntry entry = pendingEntry(caseId);
        CaseInstance instance = caseInstance(caseId, "hvac-anomaly");

        Map<String, Object> planMap = Map.of(
            "decision", "EXECUTE",
            "reasoning", "Lock the door",
            "actions", List.of(Map.of(
                "actionType", "LOCK",
                "targetDeviceId", "lock-001",
                "parameters", Map.of(),
                "rationale", "Secure entry")),
            "escalationReason", "");

        setupStandardMocks(entry, instance);
        when(llmAgent.execute(any())).thenReturn(WorkerResult.of(planMap));
        when(riskClassifier.classify(any(), any()))
            .thenReturn(new RiskDecision.GateRequired("Lock requires approval",
                true, null, null, null, null, null));
        when(queueService.findByView(AI_VIEW_ID, TENANCY)).thenReturn(List.of());

        agent.poll();

        verify(queueService).escalate(entry.getId(), TENANCY, OPERATOR_VIEW_ID);
        verify(deviceCommandFn, never()).apply(any());

        assertThat(counterValue("casehub.iot.ai.resolution.entries.processed",
                "outcome", "risk-gate", "cbr.band", "none")).isEqualTo(1.0);
    }

    @Test
    void timeoutSweep_escalatesStaleClaimedEntries() {
        UUID entryId = UUID.randomUUID();
        CaseQueueEntry staleEntry = claimedEntry(UUID.randomUUID(), entryId,
            Instant.now().minusSeconds(600));

        when(queueService.findPending(AI_VIEW_ID, TENANCY)).thenReturn(List.of());
        when(queueService.findByView(AI_VIEW_ID, TENANCY)).thenReturn(List.of(staleEntry));

        agent.poll();

        verify(queueService).escalate(entryId, TENANCY, OPERATOR_VIEW_ID);

        assertThat(counterValue("casehub.iot.ai.resolution.entries.processed",
                "outcome", "timeout", "cbr.band", "unknown")).isEqualTo(1.0);
    }

    @Test
    void statusGuard_abortsWhenEntryMoved() {
        UUID caseId = UUID.randomUUID();
        CaseQueueEntry entry = pendingEntry(caseId);
        CaseInstance instance = caseInstance(caseId, "hvac-anomaly");

        setupStandardMocks(entry, instance);
        when(llmAgent.execute(any())).thenReturn(llmExecuteResult());
        when(riskClassifier.classify(any(), any())).thenReturn(new RiskDecision.Autonomous());
        when(queueService.findByView(AI_VIEW_ID, TENANCY)).thenReturn(List.of());

        agent.poll();

        verify(deviceCommandFn, never()).apply(any());
        verify(queueService, never()).escalate(any(), any(), any());

        assertThat(counterValue("casehub.iot.ai.resolution.entries.processed",
                "outcome", "status-guard-abort", "cbr.band", "none")).isEqualTo(1.0);
    }

    @Test
    void partialWorkerFailure_escalatesWithExecutedRecord() {
        UUID caseId = UUID.randomUUID();
        CaseQueueEntry entry = pendingEntry(caseId);
        CaseInstance instance = caseInstance(caseId, "hvac-anomaly");

        Map<String, Object> planMap = Map.of(
            "decision", "EXECUTE",
            "reasoning", "Two-step fix",
            "actions", List.of(
                Map.of("actionType", "TURN_OFF", "targetDeviceId", "dev-1",
                       "parameters", Map.of(), "rationale", "Power cycle"),
                Map.of("actionType", "SET_TEMPERATURE", "targetDeviceId", "dev-2",
                       "parameters", Map.of("target", 20), "rationale", "Reset")),
            "escalationReason", "");

        setupStandardMocks(entry, instance);
        when(llmAgent.execute(any())).thenReturn(WorkerResult.of(planMap));
        when(riskClassifier.classify(any(), any())).thenReturn(new RiskDecision.Autonomous());
        when(deviceCommandFn.apply(any()))
            .thenReturn(WorkerResult.of(Map.of("result", "SUCCESS")))
            .thenReturn(WorkerResult.failed("Device unreachable"));
        when(queueService.findByView(AI_VIEW_ID, TENANCY)).thenReturn(List.of(entry));

        agent.poll();

        verify(queueService).escalate(entry.getId(), TENANCY, OPERATOR_VIEW_ID);
        verify(deviceCommandFn, times(2)).apply(any());
        verify(instance.getCaseContext(), times(2)).set(eq("aiEscalationContext"), any());

        assertThat(counterValue("casehub.iot.ai.resolution.entries.processed",
                "outcome", "partial-failure", "cbr.band", "none")).isEqualTo(1.0);
        assertThat(counterValue("casehub.iot.ai.resolution.actions.executed",
                "succeeded", "false")).isEqualTo(1.0);
        assertThat(counterValue("casehub.iot.ai.resolution.actions.executed",
                "succeeded", "true")).isEqualTo(1.0);
    }

    @Test
    void llmDeterministicFailure_escalatesImmediately() {
        UUID caseId = UUID.randomUUID();
        CaseQueueEntry entry = pendingEntry(caseId);
        CaseInstance instance = caseInstance(caseId, "hvac-anomaly");

        setupStandardMocks(entry, instance);
        when(llmAgent.execute(any())).thenThrow(new AgentException("LLM returned invalid JSON: {}"));
        when(queueService.findByView(AI_VIEW_ID, TENANCY)).thenReturn(List.of());

        agent.poll();

        verify(queueService).escalate(entry.getId(), TENANCY, OPERATOR_VIEW_ID);
        verify(llmAgent, times(1)).execute(any());

        assertThat(counterValue("casehub.iot.ai.resolution.entries.processed",
                "outcome", "llm-error", "cbr.band", "none")).isEqualTo(1.0);
        assertThat(timerCount("casehub.iot.ai.resolution.llm.call.duration",
                "outcome", "error")).isEqualTo(1);
    }

    @Test
    void llmTransientFailure_retriesThenEscalates() {
        UUID caseId = UUID.randomUUID();
        CaseQueueEntry entry = pendingEntry(caseId);
        CaseInstance instance = caseInstance(caseId, "hvac-anomaly");

        setupStandardMocks(entry, instance);
        when(llmAgent.execute(any())).thenThrow(new RuntimeException(new ConnectException("Connection refused")));
        when(queueService.findByView(AI_VIEW_ID, TENANCY)).thenReturn(List.of());

        agent.poll();

        verify(queueService).escalate(entry.getId(), TENANCY, OPERATOR_VIEW_ID);
        verify(llmAgent, times(3)).execute(any());

        assertThat(counterValue("casehub.iot.ai.resolution.llm.retries")).isEqualTo(2.0);
        assertThat(counterValue("casehub.iot.ai.resolution.entries.processed",
                "outcome", "llm-error", "cbr.band", "none")).isEqualTo(1.0);
    }

    @Test
    void disabled_noProcessing() throws Exception {
        IoTAiResolutionConfig disabledConfig = mock(IoTAiResolutionConfig.class);
        when(disabledConfig.enabled()).thenReturn(false);
        inject(agent, "config", disabledConfig);

        agent.poll();

        verifyNoInteractions(queueService);
    }

    @Test
    void claimContention_incrementsSeparateCounter() {
        UUID           caseId = UUID.randomUUID();
        CaseQueueEntry entry  = pendingEntry(caseId);

        when(queueService.findPending(AI_VIEW_ID, TENANCY)).thenReturn(List.of(entry));
        when(queueService.claim(entry.getId(), TENANCY, "iot-ai-agent"))
                .thenThrow(new IllegalStateException("Already claimed"));
        when(queueService.findByView(AI_VIEW_ID, TENANCY)).thenReturn(List.of());

        agent.poll();

        assertThat(counterValue("casehub.iot.ai.resolution.claim.contention")).isEqualTo(1.0);
        assertThat(counterValue("casehub.iot.ai.resolution.entries.processed",
                                "outcome", "claim-failed")).isEqualTo(0.0);
    }


    // --- helpers ---

    private void setupStandardMocks(CaseQueueEntry entry, CaseInstance instance) {
        CaseDefinition def = mock(CaseDefinition.class);
        when(def.getCbrConfig()).thenReturn(CbrConfig.builder().domain("iot").caseType("hvac-anomaly").featureExtractor(ctx -> Map.of()).build());

        when(queueService.findPending(AI_VIEW_ID, TENANCY)).thenReturn(List.of(entry));
        when(queueService.claim(entry.getId(), TENANCY, "iot-ai-agent")).thenAnswer(inv -> {
            entry.setStatus(QueueEntryStatus.CLAIMED);
            entry.setAssignedTo("iot-ai-agent");
            entry.setClaimedAt(Instant.now());
            return entry;
        });
        when(caseCache.get(instance.getUuid())).thenReturn(instance);
        when(definitionRegistry.findByName("hvac-anomaly")).thenReturn(Optional.of(def));
        when(retrievalService.retrieve(any(), any(), eq(TENANCY))).thenReturn(List.of());
    }

    private CaseQueueEntry pendingEntry(UUID caseId) {
        return new CaseQueueEntry(UUID.randomUUID(), caseId, TENANCY,
            AI_VIEW_ID, "iot-ai-resolution", QueueEntryStatus.PENDING, Instant.now());
    }

    private CaseQueueEntry claimedEntry(UUID caseId, UUID entryId, Instant claimedAt) {
        CaseQueueEntry e = new CaseQueueEntry(entryId, caseId, TENANCY,
            AI_VIEW_ID, "iot-ai-resolution", QueueEntryStatus.CLAIMED, Instant.now());
        e.setAssignedTo("iot-ai-agent");
        e.setClaimedAt(claimedAt);
        return e;
    }

    private CaseInstance caseInstance(UUID caseId, String caseType) {
        CaseInstance instance = new CaseInstance();
        instance.setUuid(caseId);
        CaseMetaModel meta = new CaseMetaModel();
        meta.setName(caseType);
        instance.setCaseMetaModel(meta);
        CaseContext ctx = mock(CaseContext.class);
        when(ctx.getOrDefault(any(), any())).thenReturn(Map.of());
        instance.setCaseContext(ctx);
        return instance;
    }

    private WorkerResult<Map<String, Object>> llmExecuteResult() {
        Map<String, Object> planMap = Map.of(
            "decision", "EXECUTE",
            "reasoning", "High similarity match",
            "actions", List.of(Map.of(
                "actionType", "SET_TEMPERATURE",
                "targetDeviceId", "thermo-001",
                "parameters", Map.of("target", 22),
                "rationale", "Reset temperature")),
            "escalationReason", "");
        return WorkerResult.of(planMap);
    }

    private WorkerResult<Map<String, Object>> llmEscalateResult() {
        Map<String, Object> planMap = Map.of(
            "decision", "ESCALATE",
            "reasoning", "Novel situation",
            "actions", List.of(),
            "escalationReason", "No matching pattern");
        return WorkerResult.of(planMap);
    }

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    private double counterValue(String name, String... tags) {
        io.micrometer.core.instrument.Counter counter = meterRegistry.find(name).tags(tags).counter();
        return counter != null ? counter.count() : 0.0;
    }

    private long timerCount(String name, String... tags) {
        io.micrometer.core.instrument.Timer timer = meterRegistry.find(name).tags(tags).timer();
        return timer != null ? timer.count() : 0;
    }


}
