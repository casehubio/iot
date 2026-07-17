package io.casehub.iot.webapp.cbr;

import io.casehub.api.model.cbr.CbrConfig;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.FeatureValue;
import io.casehub.neocortex.memory.cbr.PlanCbrCase;
import io.casehub.neocortex.memory.cbr.RetrievalMode;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public class IoTCbrRetrievalService {

    private final CbrCaseMemoryStore store;

    public IoTCbrRetrievalService(CbrCaseMemoryStore store) {
        this.store = Objects.requireNonNull(store, "store must not be null");
    }

    public List<ResolutionSuggestion> retrieve(CbrConfig config, Map<String, Object> rawFeatures, String tenantId) {
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");

        if (rawFeatures == null || rawFeatures.isEmpty()) {
            return List.of();
        }

        Map<String, FeatureValue> featureMap = FeatureValue.toFeatureMap(rawFeatures);

        CbrQuery query = CbrQuery.of(
                                         tenantId,
                                         new MemoryDomain(config.domain()),
                                         io.casehub.platform.api.path.Path.root(),
                                         config.caseType(),
                                         featureMap,
                                         config.topK())
                                 .withMinSimilarity(config.minSimilarity())
                                 .withWeights(config.weights())
                                 .withVectorWeight(config.vectorWeight())
                                 .withRetrievalMode(config.vectorWeight() > 0.0
                                                    ? RetrievalMode.HYBRID : RetrievalMode.FEATURE_ONLY);

        List<ScoredCbrCase<PlanCbrCase>> scored = store.retrieveSimilar(query, PlanCbrCase.class);
        return scored.stream().map(this::toSuggestion).toList();
    }

    private ResolutionSuggestion toSuggestion(ScoredCbrCase<PlanCbrCase> scored) {
        PlanCbrCase c = scored.cbrCase();
        return new ResolutionSuggestion(
                scored.caseId(),
                scored.score(),
                c.problem(),
                c.solution(),
                c.outcome(),
                c.confidence(),
                FeatureValue.toRawMap(c.features()),
                scored.featureSimilarities(),
                c.planTrace());
    }
}
