package io.casehub.iot.webapp.app.service;

import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.cbr.CbrConfig;
import io.casehub.api.model.cbr.FeatureExtractor;
import io.casehub.api.model.cbr.LambdaFeatureExtractor;
import io.casehub.engine.common.internal.model.CaseInstance;
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
import io.casehub.iot.webapp.resolution.QueueEntryDetail;
import io.casehub.iot.webapp.resolution.QueueEntrySummary;
import io.casehub.iot.webapp.spi.IoTCaseApi;
import io.casehub.iot.webapp.view.SuggestionView;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.api.view.SubjectViewSpec;
import io.casehub.platform.api.view.SubjectViewStore;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class DefaultIoTCaseApi implements IoTCaseApi {

    @Inject CurrentPrincipal principal;
    @Inject CaseInstanceCache caseInstanceCache;
    @Inject CaseDefinitionRegistry caseDefinitionRegistry;
    @Inject IoTCbrRetrievalService retrievalService;
    @Inject CaseQueueService queueService;
    @Inject CaseQueueEntryStore entryStore;
    @Inject SubjectViewStore viewStore;

    private UUID aiResolutionViewId;
    private UUID operatorAssistedViewId;
    private Map<UUID, String> viewNameMapping;

    @PostConstruct
    void init() {
        viewNameMapping = new HashMap<>();
        List<SubjectViewSpec> views = viewStore.findByTenancy(principal.tenancyId());
        for (SubjectViewSpec view : views) {
            viewNameMapping.put(view.id(), view.name());
            if ("iot-ai-resolution".equals(view.name())) {
                aiResolutionViewId = view.id();
            } else if ("iot-operator-assisted".equals(view.name())) {
                operatorAssistedViewId = view.id();
            }
        }
    }

    @Override
    public SuggestionView getCaseSuggestions(UUID caseId, String tenancyId) {
        CaseInstance instance = caseInstanceCache.get(caseId);
        if (instance == null) {
            throw new NotFoundException("Case not found: " + caseId);
        }
        String caseType = instance.getCaseMetaModel().getName();
        Optional<CaseDefinition> defOpt = caseDefinitionRegistry.findByName(caseType);
        if (defOpt.isEmpty()) {
            return new SuggestionView(caseId, caseType, 0, List.of());
        }
        CbrConfig cbrConfig = defOpt.get().getCbrConfig();
        if (cbrConfig == null) {
            return new SuggestionView(caseId, caseType, 0, List.of());
        }
        Map<String, Object> features = extractFeatures(cbrConfig, instance);
        List<ResolutionSuggestion> suggestions = retrievalService.retrieve(cbrConfig, features, tenancyId);
        return new SuggestionView(caseId, caseType, suggestions.size(), suggestions);
    }

    @Override
    public void acceptSuggestion(UUID caseId, String pastCaseId, String tenancyId) {
        CaseInstance instance = caseInstanceCache.get(caseId);
        if (instance == null) {
            throw new NotFoundException("Case not found: " + caseId);
        }
        var context = instance.getCaseContext();
        @SuppressWarnings("unchecked")
        var accepted = (java.util.Set<String>) context.getOrDefault(
                "acceptedSuggestions", new HashSet<String>());
        if (accepted.contains(pastCaseId)) {
            return;
        }
        String caseType = instance.getCaseMetaModel().getName();
        var defOpt = caseDefinitionRegistry.findByName(caseType);
        if (defOpt.isEmpty()) {
            throw new NotFoundException("Case definition not found: " + caseType);
        }
        CbrConfig cbrConfig = defOpt.get().getCbrConfig();
        if (cbrConfig == null) {
            throw new NotFoundException("No CBR config for case type: " + caseType);
        }
        var features = extractFeatures(cbrConfig, instance);
        var suggestions = retrievalService.retrieve(cbrConfig, features, tenancyId);
        var match = suggestions.stream()
                .filter(s -> pastCaseId.equals(s.caseId()))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Suggestion not found: " + pastCaseId));
        var planSteps = match.planSteps().stream()
                .map(pt -> Map.<String, Object>of(
                        "description", pt.capabilityName() + " via " + pt.workerName(),
                        "actionType", pt.capabilityName(),
                        "parameters", pt.parameters(),
                        "priority", pt.priority(),
                        "source", "cbr:" + pastCaseId))
                .toList();
        context.set("suggestedPlan", planSteps);
        var newAccepted = new HashSet<>(accepted);
        newAccepted.add(pastCaseId);
        context.set("acceptedSuggestions", newAccepted);
    }

    @Override
    public List<QueueEntrySummary> listResolutionQueue(String view, String status, String tenancyId) {
        List<UUID> viewIds = resolveViewIds(view);
        if (viewIds.isEmpty()) {
            return List.of();
        }
        List<CaseQueueEntry> entries = new ArrayList<>();
        for (UUID viewId : viewIds) {
            if ("PENDING".equals(status)) {
                entries.addAll(queueService.findPending(viewId, tenancyId));
            } else {
                entries.addAll(queueService.findByView(viewId, tenancyId));
            }
        }
        if (status != null && !"PENDING".equals(status)) {
            QueueEntryStatus filterStatus = QueueEntryStatus.valueOf(status);
            entries = entries.stream()
                    .filter(e -> e.getStatus() == filterStatus)
                    .toList();
        } else if (status == null) {
            entries = entries.stream()
                    .filter(e -> e.getStatus() != QueueEntryStatus.REVOKED)
                    .toList();
        }
        return entries.stream().map(this::toSummary).toList();
    }

    @Override
    public QueueEntryDetail getResolutionQueueEntry(UUID entryId, String tenancyId) {
        CaseQueueEntry entry = entryStore.findById(entryId)
                .filter(e -> tenancyId.equals(e.getTenancyId()))
                .orElseThrow(() -> new NotFoundException("Queue entry not found: " + entryId));
        QueueEntrySummary summary = toSummary(entry);
        CaseInstance instance = caseInstanceCache.get(entry.getCaseId());
        Map<String, Object> workingContext = Map.of();
        List<ResolutionSuggestion> suggestions = List.of();
        AiEscalationContext escalation = null;
        List<ExecutedActionResult> results = List.of();
        if (instance != null) {
            workingContext = extractWorkingContext(instance);
            String caseType = instance.getCaseMetaModel().getName();
            Optional<CaseDefinition> defOpt = caseDefinitionRegistry.findByName(caseType);
            if (defOpt.isPresent()) {
                CbrConfig cbrConfig = defOpt.get().getCbrConfig();
                if (cbrConfig != null) {
                    Map<String, Object> features = extractFeatures(cbrConfig, instance);
                    suggestions = retrievalService.retrieve(cbrConfig, features, tenancyId);
                }
            }
            escalation = (AiEscalationContext) instance.getCaseContext().get("aiEscalationContext");
            @SuppressWarnings("unchecked")
            List<ExecutedActionResult> execResults = (List<ExecutedActionResult>)
                    instance.getCaseContext().get("aiResolutionResults");
            if (execResults != null) {
                results = execResults;
            }
        }
        return new QueueEntryDetail(summary, workingContext, suggestions, escalation, results);
    }

    private QueueEntrySummary toSummary(CaseQueueEntry entry) {
        String resolvedViewName = entry.getViewName() != null
                ? entry.getViewName()
                : viewNameMapping.getOrDefault(entry.getViewId(), null);
        CaseInstance instance = caseInstanceCache.get(entry.getCaseId());
        String caseType = null, deviceId = null, deviceClass = null, roomType = null, situationId = null;
        if (instance != null) {
            caseType = instance.getCaseMetaModel().getName();
            Map<String, Object> working = extractWorkingContext(instance);
            deviceId = (String) working.get("deviceId");
            deviceClass = (String) working.get("deviceClass");
            roomType = (String) working.get("roomType");
            situationId = (String) working.get("situationId");
        }
        return new QueueEntrySummary(
                entry.getId(), entry.getCaseId(), caseType, resolvedViewName,
                entry.getStatus().name(), entry.getAssignedTo(),
                entry.getCreatedAt(), entry.getClaimedAt(), entry.getEscalatedAt(),
                entry.getPreviousViewName(), deviceId, deviceClass, roomType, situationId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractWorkingContext(CaseInstance instance) {
        Object working = instance.getCaseContext().getOrDefault("working", Map.of());
        return working instanceof Map ? (Map<String, Object>) working : Map.of();
    }

    private Map<String, Object> extractFeatures(CbrConfig config, CaseInstance instance) {
        FeatureExtractor extractor = config.featureExtractor();
        if (extractor instanceof LambdaFeatureExtractor lambda) {
            return lambda.extract(instance.getCaseContext());
        }
        return Map.of();
    }

    private List<UUID> resolveViewIds(String viewFilter) {
        if (viewFilter == null) {
            List<UUID> ids = new ArrayList<>();
            if (aiResolutionViewId != null) ids.add(aiResolutionViewId);
            if (operatorAssistedViewId != null) ids.add(operatorAssistedViewId);
            return ids;
        }
        return switch (viewFilter) {
            case "ai-resolution" -> aiResolutionViewId != null ? List.of(aiResolutionViewId) : List.of();
            case "operator-assisted" -> operatorAssistedViewId != null ? List.of(operatorAssistedViewId) : List.of();
            default -> List.of();
        };
    }
}
