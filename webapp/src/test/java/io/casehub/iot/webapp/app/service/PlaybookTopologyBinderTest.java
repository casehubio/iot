package io.casehub.iot.webapp.app.service;

import io.casehub.iot.api.PlaybookBindingEvent;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PlaybookTopologyBinderTest {

    private BroadcastProcessor<TopologyStreamEvent> broadcaster;
    private PlaybookTopologyBinder binder;
    private List<TopologyStreamEvent> captured;

    @BeforeEach
    void setUp() {
        broadcaster = BroadcastProcessor.create();
        captured = new ArrayList<>();
        broadcaster.subscribe().with(captured::add);
        binder = new PlaybookTopologyBinder(broadcaster);
    }

    @Test
    void stepStartProducesBindingStartEvent() {
        var event = new PlaybookBindingEvent.StepStart(
                "exec-1", "tenant-1", "apply-nightmode", Set.of("light-1", "therm-1"));

        binder.onBindingEvent(event);

        assertThat(captured).hasSize(1);
        var sse = captured.get(0);
        assertThat(sse.tenancyId()).isEqualTo("tenant-1");
        assertThat(sse.operation()).isEqualTo("binding-start");
        assertThat(sse.nodes()).isEmpty();
        assertThat(sse.binding()).isNotNull();
        assertThat(sse.binding().get("executionId")).isEqualTo("exec-1");
        assertThat(sse.binding().get("stepName")).isEqualTo("apply-nightmode");
        @SuppressWarnings("unchecked")
        var deviceIds = (Set<String>) sse.binding().get("deviceIds");
        assertThat(deviceIds).containsExactlyInAnyOrder("light-1", "therm-1");
    }

    @Test
    void deviceProvisionedProducesBindingUpdateEvent() {
        var event = new PlaybookBindingEvent.DeviceProvisioned(
                "exec-1", "tenant-1", "apply-nightmode", "light-1");

        binder.onBindingEvent(event);

        assertThat(captured).hasSize(1);
        var sse = captured.get(0);
        assertThat(sse.tenancyId()).isEqualTo("tenant-1");
        assertThat(sse.operation()).isEqualTo("binding-update");
        assertThat(sse.binding().get("deviceId")).isEqualTo("light-1");
        assertThat(sse.binding().get("status")).isEqualTo("PROVISIONED");
    }

    @Test
    void deviceFailedProducesBindingUpdateWithFailed() {
        var event = new PlaybookBindingEvent.DeviceFailed(
                "exec-1", "tenant-1", "apply-nightmode", "therm-1", "timeout");

        binder.onBindingEvent(event);

        var sse = captured.get(0);
        assertThat(sse.tenancyId()).isEqualTo("tenant-1");
        assertThat(sse.operation()).isEqualTo("binding-update");
        assertThat(sse.binding().get("status")).isEqualTo("FAILED");
    }

    @Test
    void stepCompleteProducesBindingCompleteOk() {
        var event = new PlaybookBindingEvent.StepComplete(
                "exec-1", "tenant-1", "apply-nightmode", 3, 0);

        binder.onBindingEvent(event);

        var sse = captured.get(0);
        assertThat(sse.tenancyId()).isEqualTo("tenant-1");
        assertThat(sse.operation()).isEqualTo("binding-complete");
        assertThat(sse.binding().get("outcome")).isEqualTo("OK");
        assertThat(sse.binding().get("provisioned")).isEqualTo(3);
    }

    @Test
    void stepFailedProducesBindingCompleteFailed() {
        var event = new PlaybookBindingEvent.StepFailed(
                "exec-1", "tenant-1", "apply-nightmode", 2, 1,
                List.of("therm-1: timeout"));

        binder.onBindingEvent(event);

        var sse = captured.get(0);
        assertThat(sse.tenancyId()).isEqualTo("tenant-1");
        assertThat(sse.operation()).isEqualTo("binding-complete");
        assertThat(sse.binding().get("outcome")).isEqualTo("FAILED");
    }

    @Test
    void clearProducesBindingClearEvent() {
        var event = new PlaybookBindingEvent.Clear("exec-1", "tenant-1");

        binder.onBindingEvent(event);

        var sse = captured.get(0);
        assertThat(sse.tenancyId()).isEqualTo("tenant-1");
        assertThat(sse.operation()).isEqualTo("binding-clear");
        assertThat(sse.binding().get("executionId")).isEqualTo("exec-1");
    }
}
