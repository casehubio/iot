package io.casehub.iot.api;

import java.util.List;
import java.util.Set;

public sealed interface ScenarioBindingEvent {
    String executionId();
    String tenancyId();

    record StepStart(
            String executionId, String tenancyId,
            String stepName, Set<String> deviceIds
    ) implements ScenarioBindingEvent {}

    record DeviceProvisioned(
            String executionId, String tenancyId,
            String stepName, String deviceId
    ) implements ScenarioBindingEvent {}

    record DeviceFailed(
            String executionId, String tenancyId,
            String stepName, String deviceId, String reason
    ) implements ScenarioBindingEvent {}

    record StepComplete(
            String executionId, String tenancyId,
            String stepName, int provisioned, int failed
    ) implements ScenarioBindingEvent {}

    record StepFailed(
            String executionId, String tenancyId,
            String stepName, int provisioned, int failed,
            List<String> failedDetails
    ) implements ScenarioBindingEvent {}

    record Clear(
            String executionId, String tenancyId
    ) implements ScenarioBindingEvent {}
}
