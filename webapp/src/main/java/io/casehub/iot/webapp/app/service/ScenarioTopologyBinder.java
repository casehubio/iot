package io.casehub.iot.webapp.app.service;

import io.casehub.iot.api.ScenarioBindingEvent;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.util.Map;

@ApplicationScoped
public class ScenarioTopologyBinder {

    private final BroadcastProcessor<TopologyStreamEvent> broadcaster;

    @Inject
    public ScenarioTopologyBinder(BroadcastProcessor<TopologyStreamEvent> broadcaster) {
        this.broadcaster = broadcaster;
    }

    void onBindingEvent(@Observes ScenarioBindingEvent event) {
        var streamEvent = switch (event) {
            case ScenarioBindingEvent.StepStart ss ->
                TopologyStreamEvent.binding("binding-start", Map.of(
                    "executionId", ss.executionId(),
                    "stepName", ss.stepName(),
                    "deviceIds", ss.deviceIds()));
            case ScenarioBindingEvent.DeviceProvisioned dp ->
                TopologyStreamEvent.binding("binding-update", Map.of(
                    "executionId", dp.executionId(),
                    "deviceId", dp.deviceId(),
                    "status", "PROVISIONED"));
            case ScenarioBindingEvent.DeviceFailed df ->
                TopologyStreamEvent.binding("binding-update", Map.of(
                    "executionId", df.executionId(),
                    "deviceId", df.deviceId(),
                    "status", "FAILED"));
            case ScenarioBindingEvent.StepComplete sc ->
                TopologyStreamEvent.binding("binding-complete", Map.of(
                    "executionId", sc.executionId(),
                    "stepName", sc.stepName(),
                    "outcome", "OK",
                    "provisioned", sc.provisioned(),
                    "failed", sc.failed()));
            case ScenarioBindingEvent.StepFailed sf ->
                TopologyStreamEvent.binding("binding-complete", Map.of(
                    "executionId", sf.executionId(),
                    "stepName", sf.stepName(),
                    "outcome", "FAILED",
                    "provisioned", sf.provisioned(),
                    "failed", sf.failed()));
            case ScenarioBindingEvent.Clear c ->
                TopologyStreamEvent.binding("binding-clear", Map.of(
                    "executionId", c.executionId()));
        };
        broadcaster.onNext(streamEvent);
    }
}
