package io.casehub.iot.api;

import java.util.List;
import java.util.Set;

public sealed interface PlaybookBindingEvent {
    String executionId();
    String tenancyId();

    record StepStart(
            String executionId, String tenancyId,
            String stepName, Set<String> deviceIds
    ) implements PlaybookBindingEvent {}

    record DeviceProvisioned(
            String executionId, String tenancyId,
            String stepName, String deviceId
    ) implements PlaybookBindingEvent {}

    record DeviceFailed(
            String executionId, String tenancyId,
            String stepName, String deviceId, String reason
    ) implements PlaybookBindingEvent {}

    record StepComplete(
            String executionId, String tenancyId,
            String stepName, int provisioned, int failed
    ) implements PlaybookBindingEvent {}

    record StepFailed(
            String executionId, String tenancyId,
            String stepName, int provisioned, int failed,
            List<String> failedDetails
    ) implements PlaybookBindingEvent {}

    record Clear(
            String executionId, String tenancyId
    ) implements PlaybookBindingEvent {}
}
