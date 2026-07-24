package io.casehub.iot.webapp.worker;

import io.casehub.work.api.WorkItemCreateRequest;
import io.casehub.work.api.WorkItemPriority;
import io.casehub.work.api.spi.WorkItemCreator;
import io.casehub.worker.api.WorkerResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

public class HumanDecisionWorkerFunction implements Function<Map<String, Object>, WorkerResult<Map<String, Object>>> {

    private final WorkItemCreator workItemCreator;

    public HumanDecisionWorkerFunction(WorkItemCreator workItemCreator) {
        this.workItemCreator = Objects.requireNonNull(workItemCreator);
    }

    @Override
    public WorkerResult<Map<String, Object>> apply(Map<String, Object> input) {
        String caseId           = str(input, "caseId");
        String caseType         = str(input, "caseType");
        String planItemId       = str(input, "planItemId");
        String situationContext = str(input, "situationContext");

        String title = (caseType != null ? caseType.replace('-', ' ') : "Review")
                       + " — " + (situationContext != null ? situationContext : "manual review");

        var request = WorkItemCreateRequest.builder()
                                           .title(title)
                                           .types(List.of("human-review"))
                                           .priority(mapPriority(str(input, "urgency")))
                                           .candidateGroups(str(input, "candidateGroups"))
                                           .callerRef("case:" + caseId + "/pi:" + planItemId)
                                           .payload(buildPayload(input))
                                           .build();

        var ref = workItemCreator.create(request);
        return WorkerResult.of(Map.of("workItemId", ref.id().toString()));
    }

    private static WorkItemPriority mapPriority(String urgency) {
        if (urgency == null) {return WorkItemPriority.MEDIUM;}
        return switch (urgency.toLowerCase()) {
            case "critical" -> WorkItemPriority.URGENT;
            case "high" -> WorkItemPriority.HIGH;
            case "low" -> WorkItemPriority.LOW;
            default -> WorkItemPriority.MEDIUM;
        };
    }

    private static String buildPayload(Map<String, Object> input) {
        var payload = new LinkedHashMap<String, Object>();
        copyIfPresent(payload, input, "caseId");
        copyIfPresent(payload, input, "caseType");
        copyIfPresent(payload, input, "workerName");
        copyIfPresent(payload, input, "deviceClass");
        copyIfPresent(payload, input, "roomType");
        copyIfPresent(payload, input, "eventTimestamp");
        copyIfPresent(payload, input, "situationId");
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                           .writeValueAsString(payload);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static void copyIfPresent(Map<String, Object> target,
                                      Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value != null) {target.put(key, value);}
    }

    private static String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof String s ? s : null;
    }
}
