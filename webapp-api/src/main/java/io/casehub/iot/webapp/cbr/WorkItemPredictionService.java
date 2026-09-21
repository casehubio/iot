package io.casehub.iot.webapp.cbr;

import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.CbrRecordStore;
import io.casehub.neocortex.memory.cbr.CbrQuery;
import io.casehub.neocortex.memory.cbr.FeatureValue;
import io.casehub.neocortex.memory.cbr.CbrFeatureRecord;
import io.casehub.neocortex.memory.cbr.RetrievalMode;
import io.casehub.neocortex.memory.cbr.CbrMatch;
import io.casehub.platform.api.path.Path;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class WorkItemPredictionService {

    private final CbrRecordStore store;
    private final int                topK;
    private final double             minSimilarity;

    public WorkItemPredictionService(CbrRecordStore store, int topK, double minSimilarity) {
        this.store         = Objects.requireNonNull(store);
        this.topK          = topK;
        this.minSimilarity = minSimilarity;
    }

    private static double percentile(List<Double> sorted, int p) {
        if (sorted.isEmpty()) {return 0;}
        double index    = (p / 100.0) * (sorted.size() - 1);
        int    lower    = (int) Math.floor(index);
        int    upper    = Math.min(lower + 1, sorted.size() - 1);
        double fraction = index - lower;
        return sorted.get(lower) + fraction * (sorted.get(upper) - sorted.get(lower));
    }

    private static String featureString(CbrMatch<CbrFeatureRecord> scored, String key) {
        FeatureValue fv = scored.cbrRecord().features().get(key);
        return fv instanceof FeatureValue.StringVal sv ? sv.value() : null;
    }

    private static Double featureNumber(CbrMatch<CbrFeatureRecord> scored, String key) {
        FeatureValue fv = scored.cbrRecord().features().get(key);
        return fv instanceof FeatureValue.NumberVal nv ? nv.value() : null;
    }

    public WorkItemPrediction predict(Map<String, Object> inputFeatures, String tenantId) {
        CbrQuery query = CbrQuery.of(
                                         tenantId,
                                         new MemoryDomain("iot"),
                                         Path.root(),
                                         "iot-work-item",
                                         FeatureValue.toFeatureMap(inputFeatures),
                                         topK
                                    ).withMinSimilarity(minSimilarity)
                                 .withRetrievalMode(RetrievalMode.FEATURE_ONLY);

        List<CbrMatch<CbrFeatureRecord>> results =
                store.retrieveSimilar(query, CbrFeatureRecord.class);

        if (results.isEmpty()) {
            return WorkItemPrediction.empty();
        }

        return aggregate(results);
    }

    private WorkItemPrediction aggregate(List<CbrMatch<CbrFeatureRecord>> results) {
        int    sampleSize          = results.size();
        var    outcomeDistribution = computeOutcomeDistribution(results);
        var    resolutionTimes     = computeResolutionTimes(results);
        var    assignees           = computeAssigneeRankings(results);
        double confidence          = computeConfidence(results);

        return new WorkItemPrediction(
                outcomeDistribution,
                resolutionTimes.p50(),
                resolutionTimes.p90(),
                assignees,
                confidence,
                sampleSize);
    }

    private Map<String, Double> computeOutcomeDistribution(
            List<CbrMatch<CbrFeatureRecord>> results) {
        Map<String, Double> weighted    = new LinkedHashMap<>();
        double              totalWeight = 0;
        for (var scored : results) {
            String status = featureString(scored, "terminalStatus");
            if (status == null) {continue;}
            double w = scored.score();
            weighted.merge(status, w, Double::sum);
            totalWeight += w;
        }
        if (totalWeight == 0) {return Map.of();}
        double total = totalWeight;
        weighted.replaceAll((k, v) -> v / total);
        return Map.copyOf(weighted);
    }

    private ResolutionTimes computeResolutionTimes(
            List<CbrMatch<CbrFeatureRecord>> results) {
        List<Double> durations = results.stream()
                                        .filter(s -> "COMPLETED".equals(featureString(s, "terminalStatus")))
                                        .map(s -> featureNumber(s, "resolutionDurationMinutes"))
                                        .filter(Objects::nonNull)
                                        .toList();

        if (durations.size() < 3) {return new ResolutionTimes(null, null);}

        List<Double> sorted = durations.stream().sorted().toList();
        return new ResolutionTimes(
                Duration.ofMinutes(Math.round(percentile(sorted, 50))),
                Duration.ofMinutes(Math.round(percentile(sorted, 90))));
    }

    private List<WorkItemPrediction.AssigneeSuggestion> computeAssigneeRankings(
            List<CbrMatch<CbrFeatureRecord>> results) {

        record AssigneeStats(int completed, int controllable, int total,
                             List<Double> completedDurations) {}

        Set<String>                controllableStatuses = Set.of("COMPLETED", "REJECTED", "FAULTED");
        Map<String, AssigneeStats> byAssignee           = new LinkedHashMap<>();

        for (var scored : results) {
            String assignee = featureString(scored, "resolvedBy");
            if (assignee == null) {continue;}
            String status = featureString(scored, "terminalStatus");
            if (status == null) {continue;}

            byAssignee.compute(assignee, (k, prev) -> {
                int c = (prev != null ? prev.completed() : 0)
                        + ("COMPLETED".equals(status) ? 1 : 0);
                int ctrl = (prev != null ? prev.controllable() : 0)
                           + (controllableStatuses.contains(status) ? 1 : 0);
                int t = (prev != null ? prev.total() : 0) + 1;
                var durations = new ArrayList<>(
                        prev != null ? prev.completedDurations() : List.of());
                if ("COMPLETED".equals(status)) {
                    Double dur = featureNumber(scored, "resolutionDurationMinutes");
                    if (dur != null) {durations.add(dur);}
                }
                return new AssigneeStats(c, ctrl, t, durations);
            });
        }

        return byAssignee.entrySet().stream()
                         .map(e -> {
                             var s = e.getValue();
                             double successRate = s.controllable() > 0
                                                  ? (double) s.completed() / s.controllable() : 0.0;
                             Duration avgDur = s.completedDurations().isEmpty() ? null
                                                                                : Duration.ofMinutes(Math.round(
                                     s.completedDurations().stream()
                                      .mapToDouble(d -> d).average().orElse(0)));
                             return new WorkItemPrediction.AssigneeSuggestion(
                                     e.getKey(), successRate, avgDur, s.total());
                         })
                         .sorted(Comparator
                                         .comparingDouble(WorkItemPrediction.AssigneeSuggestion::successRate)
                                         .reversed()
                                         .thenComparing(a -> a.avgResolutionTime() != null
                                                             ? a.avgResolutionTime() : Duration.ofDays(365)))
                         .limit(5)
                         .toList();
    }

    private double computeConfidence(List<CbrMatch<CbrFeatureRecord>> results) {
        double meanScore = results.stream()
                                  .mapToDouble(CbrMatch::score).average().orElse(0);
        double sampleFactor = Math.min(1.0,
                                       Math.log(results.size() + 1) / Math.log(2) / 4.0);
        return meanScore * sampleFactor;
    }

    private record ResolutionTimes(Duration p50, Duration p90) {}
}
