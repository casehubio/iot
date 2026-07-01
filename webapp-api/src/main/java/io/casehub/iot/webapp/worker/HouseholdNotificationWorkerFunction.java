package io.casehub.iot.webapp.worker;

import io.casehub.worker.api.WorkerResult;

import java.util.Map;
import java.util.function.Function;

/**
 * Stub implementation for household notifications.
 * Real integration with ConnectorService will be wired in Task 4's descriptors.
 */
public class HouseholdNotificationWorkerFunction implements Function<Map<String, Object>, WorkerResult> {

    @Override
    public WorkerResult apply(Map<String, Object> input) {
        String tenancyId = (String) input.get("tenancyId");
        String message = (String) input.get("message");

        // Stub: return mock success
        return WorkerResult.of(Map.of(
                "sent", true,
                "tenancyId", tenancyId != null ? tenancyId : "unknown",
                "message", message != null ? message : ""
        ));
    }
}
