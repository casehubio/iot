package io.casehub.iot.webapp.app.resolution;

import io.casehub.api.model.cbr.CbrConfig;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.engine.common.spi.event.CaseContextUpdatedEvent;
import io.casehub.engine.queue.model.CaseQueueEntry;
import io.casehub.engine.queue.service.CaseQueueService;
import io.casehub.iot.webapp.app.triage.IoTTriageConfig;
import io.casehub.iot.webapp.cbr.IoTCbrRetrievalService;
import io.casehub.iot.webapp.cbr.ResolutionSuggestion;
import io.casehub.platform.api.view.SubjectViewSpec;
import io.casehub.platform.api.view.SubjectViewStore;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

@ApplicationScoped
public class IoTCbrReEvaluationObserver {

    private static final Logger LOG = Logger.getLogger(IoTCbrReEvaluationObserver.class);
    static final long DEBOUNCE_SECONDS = 30;
    static final double MEDIUM_FLOOR_SIMILARITY = 0.5;

    @Inject CaseQueueService queueService;
    @Inject CaseInstanceCache caseCache;
    @Inject IoTCbrRetrievalService retrievalService;
    @Inject CaseDefinitionRegistry definitionRegistry;
    @Inject SubjectViewStore viewStore;
    @Inject IoTTriageConfig triageConfig;
    @Inject MeterRegistry registry;

    @Inject @ConfigProperty(name = "casehub.iot.tenancy-id")
    String tenancyId;

    private UUID aiResolutionViewId;
    private UUID operatorAssistedViewId;
    private UUID operatorManualViewId;
    private final ConcurrentHashMap<UUID, Instant> lastReEvaluation = new ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        List<SubjectViewSpec> views = viewStore.findByTenancy(tenancyId);
        aiResolutionViewId = resolveView(views, "iot-ai-resolution");
        operatorAssistedViewId = resolveView(views, "iot-operator-assisted");
        operatorManualViewId = resolveView(views, "iot-operator-manual");
    }

    private static UUID resolveView(List<SubjectViewSpec> views, String name) {
        return views.stream()
                    .filter(v -> name.equals(v.name()))
                    .map(SubjectViewSpec::id)
                    .findFirst()
                    .orElse(null);
    }

    public void onContextUpdated(@ObservesAsync CaseContextUpdatedEvent event) {
        if (aiResolutionViewId == null || operatorAssistedViewId == null
            || operatorManualViewId == null) {
            return;
        }
        if (!"working".equals(event.changedLayer())) {
            return;
        }

        UUID caseId = event.caseId();

        Instant lastEval = lastReEvaluation.get(caseId);
        if (lastEval != null && lastEval.plusSeconds(DEBOUNCE_SECONDS).isAfter(Instant.now())) {
            registry.counter("casehub.iot.ai.resolution.reevaluation.debounced").increment();
            return;
        }

        List<CaseQueueEntry> aiEntries = queueService.findByView(aiResolutionViewId, tenancyId);
        CaseQueueEntry matchingEntry = aiEntries.stream()
            .filter(e -> caseId.equals(e.getCaseId()))
            .findFirst()
            .orElse(null);

        if (matchingEntry == null) {
            lastReEvaluation.remove(caseId);
            return;
        }

        CaseInstance instance = caseCache.get(caseId);
        if (instance == null) {
            return;
        }

        String caseType = instance.getCaseMetaModel().getName();
        var defOpt = definitionRegistry.findByName(caseType);
        if (defOpt.isEmpty()) {
            return;
        }

        CbrConfig cbrConfig = defOpt.get().getCbrConfig();
        if (cbrConfig == null) {
            return;
        }

        Map<String, Object> features = extractFeatures(instance);
        List<ResolutionSuggestion> suggestions = retrievalService.retrieve(cbrConfig, features, tenancyId);
        lastReEvaluation.put(caseId, Instant.now());
        registry.counter("casehub.iot.ai.resolution.reevaluation.checked").increment();

        String oldBand = currentBand(instance);
        String newBand = computeBand(suggestions);

        if (oldBand.equals(newBand) || "high".equals(newBand)) {
            return;
        }

        UUID targetViewId;
        String targetViewName;
        if ("medium".equals(newBand)) {
            targetViewId = operatorAssistedViewId;
            targetViewName = "iot-operator-assisted";
        } else {
            targetViewId = operatorManualViewId;
            targetViewName = "iot-operator-manual";
        }

        try {
            double oldSim = bestSimilarity(instance);
            queueService.escalate(matchingEntry.getId(), tenancyId, targetViewId);
            lastReEvaluation.remove(caseId);
            updateCbrStats(instance, suggestions);
            LOG.infof("CBR re-evaluation: case %s re-routed from ai-resolution to %s "
                      + "(similarity %.2f → %.2f)", caseId, targetViewName,
                      oldSim, bestSimilarity(suggestions));
            registry.counter("casehub.iot.ai.resolution.reevaluation.rerouted",
                "from.band", oldBand, "to.band", newBand,
                "target.view", targetViewName).increment();
        } catch (IllegalStateException e) {
            LOG.debugf("Re-routing case %s failed (already moved): %s", caseId, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractFeatures(CaseInstance instance) {
        Object working = instance.getCaseContext().getOrDefault("working", Map.of());
        if (working instanceof Map) {
            return (Map<String, Object>) working;
        }
        return Map.of();
    }

    String computeBand(List<ResolutionSuggestion> suggestions) {
        if (suggestions == null || suggestions.isEmpty()) {
            return "low";
        }
        double bestSim = suggestions.stream()
            .mapToDouble(ResolutionSuggestion::similarityScore)
            .max().orElse(0.0);
        double consistency = computeOutcomeConsistency(suggestions);

        if (bestSim >= triageConfig.aiMinSimilarity()
            && consistency >= triageConfig.aiMinConsistency()) {
            return "high";
        }
        if (bestSim >= MEDIUM_FLOOR_SIMILARITY) {
            return "medium";
        }
        return "low";
    }

    private String currentBand(CaseInstance instance) {
        Map<String, Object> working = extractFeatures(instance);
        Object simObj = working.get("cbrBestSimilarity");
        Object conObj = working.get("cbrOutcomeConsistency");
        double sim = simObj instanceof Number n ? n.doubleValue() : 0.0;
        double con = conObj instanceof Number n ? n.doubleValue() : 0.0;

        if (sim >= triageConfig.aiMinSimilarity()
            && con >= triageConfig.aiMinConsistency()) {
            return "high";
        }
        if (sim >= MEDIUM_FLOOR_SIMILARITY) {
            return "medium";
        }
        return "low";
    }

    private static double computeOutcomeConsistency(List<ResolutionSuggestion> suggestions) {
        Map<String, Long> freq = suggestions.stream()
            .map(ResolutionSuggestion::outcome)
            .filter(Objects::nonNull)
            .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        if (freq.isEmpty()) {
            return 0.0;
        }
        return (double) Collections.max(freq.values()) / suggestions.size();
    }

    private void updateCbrStats(CaseInstance instance, List<ResolutionSuggestion> suggestions) {
        instance.getCaseContext().set("cbrBestSimilarity", bestSimilarity(suggestions));
        instance.getCaseContext().set("cbrMatchCount", suggestions.size());
        instance.getCaseContext().set("cbrOutcomeConsistency", computeOutcomeConsistency(suggestions));
    }

    private static double bestSimilarity(List<ResolutionSuggestion> suggestions) {
        return suggestions.stream()
            .mapToDouble(ResolutionSuggestion::similarityScore)
            .max().orElse(0.0);
    }

    private double bestSimilarity(CaseInstance instance) {
        Object v = extractFeatures(instance).get("cbrBestSimilarity");
        return v instanceof Number n ? n.doubleValue() : 0.0;
    }
}
