package io.casehub.iot.webapp.app.resolution;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.model.ai.Agent;
import io.casehub.api.model.ai.AgentException;
import io.casehub.api.model.cbr.CbrConfig;
import io.casehub.api.spi.ActionRiskClassifier;
import io.casehub.api.spi.ClassificationContext;
import io.casehub.api.spi.RiskDecision;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.engine.queue.model.CaseQueueEntry;
import io.casehub.engine.queue.model.QueueEntryStatus;
import io.casehub.engine.queue.service.CaseQueueService;

import io.casehub.iot.webapp.cbr.IoTCbrRetrievalService;
import io.casehub.iot.webapp.cbr.ResolutionSuggestion;
import io.casehub.iot.webapp.resolution.AiEscalationContext;
import io.casehub.iot.webapp.resolution.AiResolutionPlan;
import io.casehub.iot.webapp.resolution.AiResolutionPromptBuilder;
import io.casehub.iot.webapp.resolution.ConversationTranscript;
import io.casehub.iot.webapp.resolution.Decision;
import io.casehub.iot.webapp.resolution.ExecutedActionResult;
import io.casehub.iot.webapp.resolution.MultiTurnResponse;
import io.casehub.iot.webapp.resolution.PlannedActionSpec;
import io.casehub.iot.webapp.resolution.TurnSignal;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentMcpServer;
import io.casehub.platform.agent.AgentProvider;
import io.casehub.platform.agent.AgentSession;
import io.casehub.platform.agent.AgentSessionInit;
import io.casehub.platform.agent.AgentSessionLimitException;
import io.casehub.platform.api.view.SubjectViewSpec;
import io.casehub.platform.api.view.SubjectViewStore;
import io.casehub.worker.api.PlannedAction;
import io.casehub.worker.api.WorkerResult;
import io.quarkus.virtual.threads.VirtualThreads;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.function.Function;

@ApplicationScoped
public class IoTAiResolutionAgent {

    private static final Logger LOG = Logger.getLogger(IoTAiResolutionAgent.class);
    private static final Set<String> AUTONOMOUS_ACTIONS = Set.of(
            "TURN_ON", "TURN_OFF", "SET_TEMPERATURE", "SET_POSITION", "SET_VOLUME");
    private static final int MAX_RETRIES = 2;
    private static final long[] RETRY_DELAYS_MS = {5_000, 15_000};

    @Inject CaseQueueService queueService;

    @Inject CaseInstanceCache caseCache;
    @Inject IoTCbrRetrievalService retrievalService;
    @Inject ActionRiskClassifier riskClassifier;
    @Inject CaseDefinitionRegistry definitionRegistry;
    @Inject SubjectViewStore viewStore;
    @Inject IoTAiResolutionConfig config;
    @Inject ObjectMapper objectMapper;

    @Inject @ConfigProperty(name = "casehub.iot.tenancy-id")
    String tenancyId;

    @Inject @VirtualThreads
    ExecutorService virtualThreads;

    @Inject io.casehub.iot.api.spi.DeviceRegistry deviceRegistry;
    @Inject jakarta.enterprise.inject.Instance<io.casehub.iot.api.spi.DeviceProvider> deviceProviders;
    @Inject
            io.micrometer.core.instrument.MeterRegistry                               registry;

    @Inject AgentProvider agentProvider;

    Agent llmAgent;
    Function<Map<String, Object>, WorkerResult<Map<String, Object>>> deviceCommandFn;

    private UUID aiResolutionViewId;
    private UUID operatorAssistedViewId;
    private Semaphore llmSemaphore;
    private Semaphore sessionSemaphore;
    private final ConcurrentHashMap<UUID, Instant> activeSessions = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger pendingCount = new java.util.concurrent.atomic.AtomicInteger(0);


    @PostConstruct
    void init() {
        List<SubjectViewSpec> views = viewStore.findByTenancy(tenancyId);
        aiResolutionViewId     = views.stream()
                                      .filter(v -> "iot-ai-resolution".equals(v.name()))
                                      .map(SubjectViewSpec::id)
                                      .findFirst()
                                      .orElse(null);
        operatorAssistedViewId = views.stream()
                                      .filter(v -> "iot-operator-assisted".equals(v.name()))
                                      .map(SubjectViewSpec::id)
                                      .findFirst()
                                      .orElse(null);
        llmSemaphore           = new Semaphore(config.maxConcurrentLlmCalls());
        sessionSemaphore       = new Semaphore(config.maxConcurrentSessions());

        if (llmAgent == null) {
            llmAgent = Agent.builder()
                            .systemPrompt("You are an IoT resolution agent. Given a situation and past resolutions, "
                                          + "decide whether to EXECUTE a resolution plan or ESCALATE to a human operator. "
                                          + "Respond with a JSON object containing: decision (EXECUTE or ESCALATE), reasoning, "
                                          + "actions (list of action specs with actionType, targetDeviceId, parameters, rationale), "
                                          + "and escalationReason (if escalating).")
                            .userMessage("{{prompt}}")
                            .model(config.modelType())
                            .responseSchema(AiResolutionPlan.class)
                            .build();
        }

        if (deviceCommandFn == null) {
            deviceCommandFn = new io.casehub.iot.webapp.worker.DeviceCommandWorkerFunction(
                    deviceProviders, deviceRegistry);
        }

        io.micrometer.core.instrument.Gauge.builder("casehub.iot.ai.resolution.semaphore.available",
                                                    llmSemaphore, Semaphore::availablePermits)
                                           .register(registry);

        io.micrometer.core.instrument.Gauge.builder("casehub.iot.ai.resolution.queue.pending",
                                                    pendingCount, java.util.concurrent.atomic.AtomicInteger::get)
                                           .register(registry);
    }

    public void poll() {
        if (!config.enabled() || aiResolutionViewId == null || operatorAssistedViewId == null) {
            return;
        }
        io.micrometer.core.instrument.Timer.Sample sample = io.micrometer.core.instrument.Timer.start(registry);
        try {
            processNewEntries();
            sweepStaleEntries();
        } finally {
            sample.stop(io.micrometer.core.instrument.Timer.builder("casehub.iot.ai.resolution.poll.duration")
                                                           .register(registry));
        }
    }

    private void processNewEntries() {
        List<CaseQueueEntry> pending = queueService.findPending(aiResolutionViewId, tenancyId);
        pendingCount.set(pending.size());
        for (CaseQueueEntry entry : pending) {
            virtualThreads.submit(() -> {
                try {
                    processEntry(entry);
                } catch (Exception e) {
                    LOG.errorf(e, "Unexpected error processing queue entry %s", entry.getId());
                }
            });
        }
    }

    private void processEntry(CaseQueueEntry pendingEntry) {
        CaseQueueEntry entry;
        try {
            entry = queueService.claim(pendingEntry.getId(), tenancyId, config.agentId());
        } catch (IllegalStateException e) {
            LOG.debugf("Entry %s already claimed — skipping", pendingEntry.getId());
            registry.counter("casehub.iot.ai.resolution.claim.contention").increment();
            return;
        }

        io.micrometer.core.instrument.Timer.Sample sample           = io.micrometer.core.instrument.Timer.start(registry);
        String                                     outcome          = "error";
        String                                     band             = "unknown";
        boolean                                    cbrConfigPresent = false;

        try {
            UUID         caseId   = entry.getCaseId();
            CaseInstance instance = caseCache.get(caseId);
            if (instance == null) {
                LOG.warnf("Case %s not found in cache — releasing entry", caseId);
                outcome = "case-not-found";
                return;
            }

            String caseType = instance.getCaseMetaModel().getName();
            var    defOpt   = definitionRegistry.findByName(caseType);
            if (defOpt.isEmpty()) {
                escalateWithReason(entry, "Case definition not found: " + caseType, instance, List.of());
                outcome = "case-not-found";
                return;
            }

            CbrConfig cbrConfig = defOpt.get().getCbrConfig();
            cbrConfigPresent = cbrConfig != null;
            List<ResolutionSuggestion> suggestions;
            if (cbrConfigPresent) {
                var features = extractFeatures(instance);
                suggestions = retrievalService.retrieve(cbrConfig, features, tenancyId);
            } else {
                suggestions = List.of();
            }
            band = cbrBand(suggestions, cbrConfigPresent);

            writePreLlmContext(instance, suggestions);

            String mode = config.conversationMode();
            if ("single".equals(mode)) {
                outcome = processSingleShot(entry, instance, caseType, suggestions);
            } else {
                outcome = processMultiTurn(entry, instance, caseType, suggestions);
            }
        } finally {
            sample.stop(io.micrometer.core.instrument.Timer.builder("casehub.iot.ai.resolution.entry.duration")
                                                           .tag("outcome", outcome)
                                                           .register(registry));
            registry.counter("casehub.iot.ai.resolution.entries.processed",
                             "outcome", outcome, "cbr.band", band).increment();
        }
    }

    private AiResolutionPlan callLlmWithRetry(CaseQueueEntry entry, CaseInstance instance,
                                               List<ResolutionSuggestion> suggestions) {
        String prompt = AiResolutionPromptBuilder.build(
                extractFeatures(instance), suggestions, AUTONOMOUS_ACTIONS);

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                llmSemaphore.acquire();
                io.micrometer.core.instrument.Timer.Sample llmSample = io.micrometer.core.instrument.Timer.start(registry);
                try {
                    WorkerResult<Map<String, Object>> result =
                            llmAgent.execute(Map.of("prompt", prompt));
                    llmSample.stop(io.micrometer.core.instrument.Timer.builder("casehub.iot.ai.resolution.llm.call.duration")
                                                                      .tag("outcome", "success").register(registry));
                    return objectMapper.convertValue(result.output(), AiResolutionPlan.class);
                } catch (Exception e) {
                    llmSample.stop(io.micrometer.core.instrument.Timer.builder("casehub.iot.ai.resolution.llm.call.duration")
                                                                      .tag("outcome", "error").register(registry));
                    throw e;
                } finally {
                    llmSemaphore.release();
                }
            } catch (AgentException e) {
                escalateWithReason(entry, "LLM error: " + e.getMessage(), instance, suggestions);
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                escalateWithReason(entry, "Interrupted waiting for LLM permit", instance, suggestions);
                return null;
            } catch (Exception e) {
                if (isTransient(e) && attempt < MAX_RETRIES) {
                    LOG.warnf("Transient LLM failure (attempt %d/%d): %s",
                              attempt + 1, MAX_RETRIES + 1, e.getMessage());
                    registry.counter("casehub.iot.ai.resolution.llm.retries").increment();
                    try {
                        Thread.sleep(RETRY_DELAYS_MS[attempt]);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        escalateWithReason(entry, "Interrupted during retry backoff", instance, suggestions);
                        return null;
                    }
                } else {
                    escalateWithReason(entry, "LLM failure after retries: " + e.getMessage(),
                                       instance, suggestions);
                    return null;
                }
            }
        }
        return null;
    }

    private boolean riskCheckPasses(AiResolutionPlan plan, CaseQueueEntry entry,
                                     CaseInstance instance, String caseType,
                                     List<ResolutionSuggestion> suggestions) {
        for (PlannedActionSpec spec : plan.actions()) {
            PlannedAction action = PlannedAction.of(spec.rationale(), spec.actionType());
            ClassificationContext ctx = new ClassificationContext(
                    config.agentId(), instance.getUuid(), tenancyId,
                    caseType, spec.actionType(), "ai-resolution");
            RiskDecision decision = riskClassifier.classify(action, ctx);
            if (decision instanceof RiskDecision.GateRequired gate) {
                LOG.infof("Action %s requires gate: %s — escalating entire case",
                        spec.actionType(), gate.reason());
                updateEscalationContext(instance, "Risk gate: " + gate.reason(),
                        suggestions, plan.reasoning(), plan.actions(), null);
                queueService.escalate(entry.getId(), tenancyId, operatorAssistedViewId);
                return false;
            }
        }
        return true;
    }

    private String processSingleShot(CaseQueueEntry entry, CaseInstance instance,
                                     String caseType, List<ResolutionSuggestion> suggestions) {
        AiResolutionPlan plan = callLlmWithRetry(entry, instance, suggestions);
        if (plan == null) {
            return "llm-error";
        }
        if (plan.decision() == Decision.ESCALATE) {
            escalateWithReason(entry, plan.escalationReason(), instance, suggestions);
            return "llm-escalated";
        }
        if (!riskCheckPasses(plan, entry, instance, caseType, suggestions)) {
            return "risk-gate";
        }
        if (!statusGuardPasses(entry)) {
            LOG.infof("Entry %s was moved by timeout sweep — aborting execution", entry.getId());
            return "status-guard-abort";
        }
        return executeActions(plan, entry, instance, suggestions);
    }

    private String processMultiTurn(CaseQueueEntry entry, CaseInstance instance,
                                     String caseType, List<ResolutionSuggestion> suggestions) {
        if (!sessionSemaphore.tryAcquire()) {
            LOG.info("Session semaphore full — falling back to single-shot");
            registry.counter("casehub.iot.ai.resolution.session.fallback",
                    "reason", "semaphore").increment();
            return processSingleShot(entry, instance, caseType, suggestions);
        }

        activeSessions.put(entry.getCaseId(), Instant.now());
        AgentSession session = null;
        try {
            session = agentProvider.openSession(AgentSessionInit.of(
                    MULTI_TURN_SYSTEM_PROMPT,
                    java.time.Duration.ofSeconds(config.timeoutSeconds()),
                    "iot-resolution-" + entry.getCaseId()));

            io.micrometer.core.instrument.Timer.Sample convSample = io.micrometer.core.instrument.Timer.start(registry);
            var collector = new AgentEventCollector(objectMapper);
            var state = new MultiTurnResolutionState(new ConversationTranscript());

            int maxTurns = config.maxConversationTurns();
            for (int turn = 0; turn < maxTurns && !state.isTerminal(); turn++) {
                String query = state.isFirstTurn()
                        ? buildInitialQuery(instance, suggestions)
                        : buildFollowUpQuery(state);

                io.smallrye.mutiny.Multi<AgentEvent> events = session.query(query);
                CollectedTurn collected = collector.collect(events);
                state.addTurn(query, collected);

                for (var tc : collected.toolCalls()) {
                    registry.counter("casehub.iot.ai.resolution.conversation.tool.calls",
                            "tool", tc.name()).increment();
                }

                if (collected.response() == null) {
                    state.withEscalation("Failed to parse LLM response: " + collected.rawText());
                } else {
                    switch (collected.response().signal()) {
                        case RESOLVED -> state.withResolution(collected.response().actions());
                        case ESCALATE -> state.withEscalation(collected.response().escalationReason());
                        case CONTINUE -> {}
                    }
                }
            }

            if (!state.isTerminal()) {
                state.withEscalation("Max conversation turns exceeded");
            }

            instance.getCaseContext().set("aiConversationTranscript", state.transcript());

            String convOutcome = state.resolution() != null ? "resolved" : "escalated";
            convSample.stop(io.micrometer.core.instrument.Timer.builder("casehub.iot.ai.resolution.conversation.duration")
                    .tag("outcome", convOutcome).tag("mode", config.conversationMode())
                    .register(registry));
            registry.summary("casehub.iot.ai.resolution.conversation.turns",
                    "outcome", convOutcome).record(state.turnCount());
            registry.counter("casehub.iot.ai.resolution.conversation.tokens",
                    "type", "input").increment(state.transcript().totalInputTokens());
            registry.counter("casehub.iot.ai.resolution.conversation.tokens",
                    "type", "output").increment(state.transcript().totalOutputTokens());

            if (state.resolution() != null) {
                AiResolutionPlan plan = new AiResolutionPlan(
                        Decision.EXECUTE, "multi-turn resolved",
                        state.resolution(), null);
                if (!riskCheckPasses(plan, entry, instance, caseType, suggestions)) {
                    return "risk-gate";
                }
                if (!statusGuardPasses(entry)) {
                    LOG.infof("Entry %s was moved by timeout sweep — aborting execution", entry.getId());
                    return "status-guard-abort";
                }
                return executeActions(plan, entry, instance, suggestions);
            } else {
                updateEscalationContext(instance, state.escalationReason(),
                        suggestions, null, null, null, state.transcript());
                queueService.escalate(entry.getId(), tenancyId, operatorAssistedViewId);
                return "multi-turn-escalated";
            }
        } catch (AgentSessionLimitException e) {
            LOG.warn("AgentSession limit reached — falling back to single-shot");
            registry.counter("casehub.iot.ai.resolution.session.fallback",
                    "reason", "limit").increment();
            return processSingleShot(entry, instance, caseType, suggestions);
        } catch (Exception e) {
            LOG.errorf(e, "Multi-turn conversation failed for entry %s", entry.getId());
            escalateWithReason(entry, "Multi-turn error: " + e.getMessage(), instance, suggestions);
            return "multi-turn-error";
        } finally {
            if (session != null) {
                session.close(java.time.Duration.ofSeconds(5));
            }
            activeSessions.remove(entry.getCaseId());
            sessionSemaphore.release();
        }
    }

    private static final String MULTI_TURN_SYSTEM_PROMPT =
            "You are an IoT resolution agent with access to device query tools. "
            + "Gather information, analyze the situation, and propose a resolution plan. "
            + "Respond with JSON: {\"signal\":\"CONTINUE|RESOLVED|ESCALATE\","
            + "\"reasoning\":\"...\",\"actions\":[{\"actionType\":\"...\",\"targetDeviceId\":\"...\","
            + "\"parameters\":{},\"rationale\":\"...\"}],\"escalationReason\":\"...\","
            + "\"informationNeeded\":\"...\"}";

    private String buildInitialQuery(CaseInstance instance, List<ResolutionSuggestion> suggestions) {
        return AiResolutionPromptBuilder.build(
                extractFeatures(instance), suggestions, AUTONOMOUS_ACTIONS)
               + "\n\nRespond with JSON matching the schema in the system prompt.";
    }

    private String buildFollowUpQuery(MultiTurnResolutionState state) {
        var last = state.lastResponse();
        if (last != null && last.informationNeeded() != null) {
            return "You requested: " + last.informationNeeded()
                   + "\nUse the available tools to gather this information, "
                   + "then respond with your updated assessment as JSON.";
        }
        return "Continue your analysis. Respond with JSON.";
    }

    private boolean statusGuardPasses(CaseQueueEntry entry) {
        return queueService.findByView(aiResolutionViewId, tenancyId).stream()
                           .anyMatch(e -> e.getId().equals(entry.getId())
                                          && e.getStatus() == QueueEntryStatus.CLAIMED);
    }

    private String executeActions(AiResolutionPlan plan, CaseQueueEntry entry,
                                  CaseInstance instance, List<ResolutionSuggestion> suggestions) {
        List<ExecutedActionResult> results      = new ArrayList<>();
        boolean                    allSucceeded = true;

        io.micrometer.core.instrument.Timer.Sample actionSample = io.micrometer.core.instrument.Timer.start(registry);
        for (PlannedActionSpec spec : plan.actions()) {
            Map<String, Object> input = Map.of(
                    "targetDeviceId", spec.targetDeviceId(),
                    "action", spec.actionType(),
                    "parameters", spec.parameters());

            WorkerResult<Map<String, Object>> workerResult = deviceCommandFn.apply(input);

            if (workerResult.outcome() instanceof io.casehub.worker.api.WorkerOutcome.Failed<?> failed) {
                results.add(new ExecutedActionResult(spec, false, failed.reason()));
                registry.counter("casehub.iot.ai.resolution.actions.executed",
                                 "succeeded", "false").increment();
                allSucceeded = false;
                break;
            } else {
                String workerOutcome = workerResult.output() != null
                                       ? workerResult.output().toString() : "SUCCESS";
                results.add(new ExecutedActionResult(spec, true, workerOutcome));
                registry.counter("casehub.iot.ai.resolution.actions.executed",
                                 "succeeded", "true").increment();
            }
        }

        String executionOutcome = allSucceeded ? "success" : "partial-failure";
        actionSample.stop(io.micrometer.core.instrument.Timer.builder("casehub.iot.ai.resolution.action.execution.duration")
                                                             .tag("outcome", executionOutcome).register(registry));

        if (allSucceeded) {
            instance.getCaseContext().set("aiResolutionResults", results);
            LOG.infof("AI resolution succeeded for case %s — %d actions executed",
                      instance.getUuid(), results.size());
            return "executed";
        } else {
            instance.getCaseContext().set("aiResolutionResults", results);
            updateEscalationContext(instance, "Partial worker failure",
                                    suggestions, plan.reasoning(), plan.actions(), results);
            queueService.escalate(entry.getId(), tenancyId, operatorAssistedViewId);
            LOG.warnf("Partial failure for case %s — %d/%d actions executed, escalating",
                      instance.getUuid(), results.stream().filter(ExecutedActionResult::succeeded).count(),
                      plan.actions().size());
            return "partial-failure";
        }
    }

    private void sweepStaleEntries() {
        List<CaseQueueEntry> all       = queueService.findByView(aiResolutionViewId, tenancyId);
        Instant              threshold = Instant.now().minusSeconds(config.timeoutSeconds());
        for (CaseQueueEntry entry : all) {
            if (activeSessions.containsKey(entry.getCaseId())) {
                continue;
            }
            if (entry.getStatus() == QueueEntryStatus.CLAIMED
                && entry.getClaimedAt() != null
                && entry.getClaimedAt().isBefore(threshold)) {
                LOG.warnf("Timeout sweep: escalating stale entry %s (claimed at %s)",
                          entry.getId(), entry.getClaimedAt());
                registry.counter("casehub.iot.ai.resolution.entries.processed",
                                 "outcome", "timeout", "cbr.band", "unknown").increment();
                queueService.escalate(entry.getId(), tenancyId, operatorAssistedViewId);
            }
        }
    }

    private void writePreLlmContext(CaseInstance instance, List<ResolutionSuggestion> suggestions) {
        var ctx = new AiEscalationContext("ai-resolution-in-progress", suggestions, null, null, null, null);
        instance.getCaseContext().set("aiEscalationContext", ctx);
    }

    private void escalateWithReason(CaseQueueEntry entry, String reason,
                                     CaseInstance instance, List<ResolutionSuggestion> suggestions) {
        updateEscalationContext(instance, reason, suggestions, null, null, null);
        queueService.escalate(entry.getId(), tenancyId, operatorAssistedViewId);
    }

    private void updateEscalationContext(CaseInstance instance, String reason,
                                          List<ResolutionSuggestion> suggestions,
                                          String analysis, List<PlannedActionSpec> plan,
                                          List<ExecutedActionResult> executed) {
        updateEscalationContext(instance, reason, suggestions, analysis, plan, executed, null);
    }

    private void updateEscalationContext(CaseInstance instance, String reason,
                                          List<ResolutionSuggestion> suggestions,
                                          String analysis, List<PlannedActionSpec> plan,
                                          List<ExecutedActionResult> executed,
                                          ConversationTranscript transcript) {
        var ctx = new AiEscalationContext(reason, suggestions, analysis, plan, executed, transcript);
        instance.getCaseContext().set("aiEscalationContext", ctx);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractFeatures(CaseInstance instance) {
        Object working = instance.getCaseContext().getOrDefault("working", Map.of());
        if (working instanceof Map) {
            return (Map<String, Object>) working;
        }
        return Map.of();
    }

    private static boolean isTransient(Exception e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof HttpTimeoutException || cause instanceof ConnectException) {
                return true;
            }
            String msg = cause.getMessage();
            if (msg != null && (msg.contains("429") || msg.contains("503") || msg.contains("502"))) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    boolean isReady() {
        return config.enabled()
               && aiResolutionViewId != null
               && operatorAssistedViewId != null;
    }

    Map<String, Object> healthData() {
        return Map.of(
                "enabled", config.enabled(),
                "aiResolutionViewResolved", aiResolutionViewId != null,
                "operatorAssistedViewResolved", operatorAssistedViewId != null,
                "semaphorePermits", llmSemaphore != null ? llmSemaphore.availablePermits() : 0);
    }

    static String cbrBand(List<ResolutionSuggestion> suggestions, boolean cbrConfigPresent) {
        if (suggestions == null || !cbrConfigPresent) {
            return "unknown";
        }
        if (suggestions.isEmpty()) {
            return "none";
        }
        double max = suggestions.stream()
                                .mapToDouble(ResolutionSuggestion::similarityScore)
                                .max()
                                .orElse(0.0);
        if (max >= 0.85) {return "high";}
        if (max >= 0.6) {return "medium";}
        return "low";
    }


}
