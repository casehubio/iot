package io.casehub.iot.webapp.cbr;

import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.FeatureValue;
import io.casehub.neocortex.memory.cbr.FeatureVectorCbrCase;
import io.casehub.neocortex.memory.cbr.RetrievalMode;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import io.casehub.platform.api.path.Path;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public class SuppressionEvaluator {

    private final CbrCaseMemoryStore store;
    private final SuppressionConfig config;

    public SuppressionEvaluator(CbrCaseMemoryStore store, SuppressionConfig config) {
        this.store = Objects.requireNonNull(store, "store");
        this.config = Objects.requireNonNull(config, "config");
    }

    public SuppressionAssessment assess(String situationId, Map<String, Object> features, String tenantId) {
        Map<String, FeatureValue> featureMap = FeatureValue.toFeatureMap(features);

        CbrQuery query = CbrQuery.of(
                        tenantId,
                        new MemoryDomain("iot"),
                        Path.root(),
                        "iot-dismissal:" + situationId,
                        featureMap,
                        config.topK())
                .withMinSimilarity(config.minSimilarity())
                .withRetrievalMode(RetrievalMode.FEATURE_ONLY);

        List<ScoredCbrCase<FeatureVectorCbrCase>> results =
                store.retrieveSimilar(query, FeatureVectorCbrCase.class);

        if (results.isEmpty() || results.size() < config.minCases()) {
            return new SuppressionAssessment(SuppressionTier.NONE, 0.0,
                    results.size(), 0, 0.0);
        }

        int dismissed = 0;
        double scoreSum = 0.0;
        for (var scored : results) {
            if ("dismissed".equals(scored.cbrCase().outcome())) {
                dismissed++;
            }
            scoreSum += scored.score();
        }

        double rate = (double) dismissed / results.size();
        double avgSimilarity = scoreSum / results.size();

        SuppressionTier tier;
        if (dismissed == 0) {
            tier = SuppressionTier.NONE;
        } else if (rate >= config.fullThreshold()) {
            tier = SuppressionTier.SUPPRESS;
        } else if (rate >= config.demotionThreshold()) {
            tier = SuppressionTier.DEMOTE;
        } else {
            tier = SuppressionTier.ANNOTATE;
        }

        return new SuppressionAssessment(tier, rate, results.size(), dismissed, avgSimilarity);
    }
}
