package io.casehub.iot.webapp.cbr;

import io.casehub.neocortex.memory.EraseRequest;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.CbrCase;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.CbrFeatureSchema;
import io.casehub.neocortex.memory.cbr.CbrOutcome;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.CbrRetentionPolicy;
import io.casehub.neocortex.memory.cbr.FeatureValue;
import io.casehub.neocortex.memory.cbr.FeatureVectorCbrCase;
import io.casehub.neocortex.memory.cbr.ScoredCbrCase;
import io.casehub.platform.api.path.Path;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.casehub.neocortex.memory.cbr.FeatureValue.number;
import static io.casehub.neocortex.memory.cbr.FeatureValue.string;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class WorkItemPredictionServiceTest {

    @Test
    void emptyResults_returnsEmptyPrediction() {
        var service = service(List.of());

        var prediction = service.predict(Map.of("caseType", "hvac-anomaly"), "tenant-1");

        assertThat(prediction.sampleSize()).isZero();
        assertThat(prediction.confidence()).isZero();
        assertThat(prediction.outcomeDistribution()).isEmpty();
        assertThat(prediction.resolutionTimeP50()).isNull();
        assertThat(prediction.resolutionTimeP90()).isNull();
        assertThat(prediction.suggestedAssignees()).isEmpty();
    }

    @Test
    void singleCompletedResult_fullPrediction() {
        var service = service(List.of(scoredCase(0.9, "COMPLETED", 120.0, "tech-1")));

        var prediction = service.predict(Map.of("caseType", "hvac-anomaly"), "tenant-1");

        assertThat(prediction.sampleSize()).isEqualTo(1);
        assertThat(prediction.outcomeDistribution()).containsEntry("COMPLETED", 1.0);
        assertThat(prediction.suggestedAssignees()).hasSize(1);
        assertThat(prediction.suggestedAssignees().getFirst().assigneeId()).isEqualTo("tech-1");
    }

    @Test
    void diverseOutcomes_weightedDistribution() {
        var service = service(List.of(
                scoredCase(0.9, "COMPLETED", 120.0, "tech-1"),
                scoredCase(0.8, "COMPLETED", 180.0, "tech-2"),
                scoredCase(0.7, "REJECTED", 60.0, "tech-1")));

        var prediction = service.predict(Map.of("caseType", "hvac-anomaly"), "tenant-1");

        assertThat(prediction.sampleSize()).isEqualTo(3);
        assertThat(prediction.outcomeDistribution().get("COMPLETED"))
                .isGreaterThan(prediction.outcomeDistribution().get("REJECTED"));
        double total = prediction.outcomeDistribution().values().stream()
                .mapToDouble(d -> d).sum();
        assertThat(total).isCloseTo(1.0, within(0.001));
    }

    @Test
    void resolutionTime_onlyCompletedItems() {
        var service = service(List.of(
                scoredCase(0.9, "COMPLETED", 120.0, "tech-1"),
                scoredCase(0.8, "COMPLETED", 180.0, "tech-2"),
                scoredCase(0.7, "COMPLETED", 60.0, "tech-3"),
                scoredCase(0.6, "CANCELLED", 300.0, null)));

        var prediction = service.predict(Map.of("caseType", "x"), "t");

        assertThat(prediction.resolutionTimeP50()).isNotNull();
        assertThat(prediction.resolutionTimeP90()).isNotNull();
        assertThat(prediction.resolutionTimeP90()).isGreaterThanOrEqualTo(prediction.resolutionTimeP50());
    }

    @Test
    void resolutionTime_fewerThan3Completed_null() {
        var service = service(List.of(
                scoredCase(0.9, "COMPLETED", 120.0, "tech-1"),
                scoredCase(0.8, "CANCELLED", 300.0, null)));

        var prediction = service.predict(Map.of("caseType", "x"), "t");

        assertThat(prediction.resolutionTimeP50()).isNull();
        assertThat(prediction.resolutionTimeP90()).isNull();
    }

    @Test
    void assigneeRanking_excludesNullResolvedBy() {
        var service = service(List.of(
                scoredCase(0.9, "COMPLETED", 120.0, "tech-1"),
                scoredCase(0.8, "CANCELLED", 300.0, null)));

        var prediction = service.predict(Map.of("caseType", "x"), "t");

        assertThat(prediction.suggestedAssignees()).hasSize(1);
        assertThat(prediction.suggestedAssignees().getFirst().assigneeId()).isEqualTo("tech-1");
    }

    @Test
    void assigneeRanking_controllableSuccessRate() {
        var service = service(List.of(
                scoredCase(0.9, "COMPLETED", 120.0, "tech-1"),
                scoredCase(0.9, "COMPLETED", 60.0, "tech-1"),
                scoredCase(0.9, "COMPLETED", 180.0, "tech-1"),
                scoredCase(0.9, "REJECTED", 30.0, "tech-1"),
                scoredCase(0.9, "CANCELLED", 300.0, "tech-1")));

        var prediction = service.predict(Map.of("caseType", "x"), "t");

        var assignee = prediction.suggestedAssignees().getFirst();
        assertThat(assignee.successRate()).isCloseTo(0.75, within(0.001));
        assertThat(assignee.taskCount()).isEqualTo(5);
    }

    @Test
    void confidence_scalesWithSampleSize() {
        var single = service(List.of(scoredCase(0.9, "COMPLETED", 120.0, "t")));
        var many = new ArrayList<ScoredCbrCase<FeatureVectorCbrCase>>();
        for (int i = 0; i < 16; i++) many.add(scoredCase(0.9, "COMPLETED", 120.0, "t"));
        var large = service(many);

        var predSingle = single.predict(Map.of("caseType", "x"), "t");
        var predMany = large.predict(Map.of("caseType", "x"), "t");

        assertThat(predMany.confidence()).isGreaterThan(predSingle.confidence());
    }

    // --- helpers ---

    private static WorkItemPredictionService service(
            List<ScoredCbrCase<FeatureVectorCbrCase>> results) {
        return new WorkItemPredictionService(new StubCbrStore(results), 20, 0.3);
    }

    private static ScoredCbrCase<FeatureVectorCbrCase> scoredCase(
            double score, String status, double durationMinutes, String assignee) {
        var featureMap = new LinkedHashMap<String, FeatureValue>();
        featureMap.put("terminalStatus", string(status));
        featureMap.put("resolutionDurationMinutes", number(durationMinutes));
        if (assignee != null) featureMap.put("resolvedBy", string(assignee));
        var cbrCase = new FeatureVectorCbrCase(
                "work item title", "resolution", status, 1.0, featureMap);
        return new ScoredCbrCase<>(cbrCase, score);
    }

    private record StubCbrStore(
            List<ScoredCbrCase<FeatureVectorCbrCase>> results
    ) implements CbrCaseMemoryStore {

        @Override
        @SuppressWarnings("unchecked")
        public <C extends CbrCase> List<ScoredCbrCase<C>> retrieveSimilar(
                CbrQuery query, Class<C> caseType) {
            return (List<ScoredCbrCase<C>>) (List<?>) results;
        }

        @Override public void registerSchema(CbrFeatureSchema schema) {}
        @Override public String store(CbrCase c, String ct, String eid,
                MemoryDomain d, String tid, String cid, Path scope) { return "id"; }
        @Override public Integer erase(EraseRequest r) { return 0; }
        @Override public Integer eraseEntity(String eid, String tid) { return 0; }
        @Override public Integer eraseByScope(Path scope, String tid) { return 0; }
        @Override public void recordOutcome(String cid, String tid, CbrOutcome o) {}
        @Override public Integer purge(CbrRetentionPolicy p) { return 0; }
        @Override public void supersede(String cid, String tid, String scid, String r) {}
        @Override public void reinstate(String cid, String tid) {}

        @Override
        public java.util.List<io.casehub.neocortex.memory.cbr.SupersessionStatus> findSupersededCases(String cid, MemoryDomain d) {return java.util.List.of();}

        @Override
        public io.casehub.neocortex.memory.cbr.SupersessionStatus getSupersessionStatus(String cid, String tid) {return null;}
    }
}
