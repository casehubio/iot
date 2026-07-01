package io.casehub.iot.webapp.worker;

import io.casehub.worker.api.WorkerResult;

import java.util.Map;
import java.util.function.Function;

/**
 * Stub implementation for human decision WorkItems.
 * Real integration with WorkBroker will be wired in Task 4's descriptors.
 */
public class HumanDecisionWorkerFunction implements Function<Map<String, Object>, WorkerResult> {

    @Override
    public WorkerResult apply(Map<String, Object> input) {
        String situationContext = (String) input.get("situationContext");
        @SuppressWarnings("unchecked")
        Map<String, Object> options = (Map<String, Object>) input.getOrDefault("options", Map.of());

        // Stub: return mock outcome
        return WorkerResult.of(Map.of(
                "decision", "approved",
                "situationContext", situationContext != null ? situationContext : "",
                "workItemCreated", true
        ));
    }
}
