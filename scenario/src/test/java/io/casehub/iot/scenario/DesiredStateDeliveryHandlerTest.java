package io.casehub.iot.scenario;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.ProvisionResult;
import io.casehub.iot.api.ScenarioBindingEvent;
import io.casehub.iot.desiredstate.IoTActualStateAdapter;
import io.casehub.iot.desiredstate.IoTGoalCompiler;
import io.casehub.iot.desiredstate.IoTGoals;
import io.casehub.iot.desiredstate.IoTNodeProvisioner;
import io.casehub.iot.desiredstate.IoTPresetResolver;
import io.casehub.iot.testing.Fixtures;
import io.casehub.iot.testing.MockDeviceProvider;
import io.casehub.iot.testing.MockDeviceRegistry;
import io.casehub.pages.scenario.DeliveryContext;
import io.casehub.pages.scenario.StepOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DesiredStateDeliveryHandlerTest {

    private static final String TENANCY_ID = "default-tenant";
    private static final DeliveryContext CTX = mock(DeliveryContext.class);

    private MockDeviceProvider provider;
    private MockDeviceRegistry registry;
    private IoTPresetResolver presetResolver;
    private IoTGoalCompiler compiler;
    private IoTActualStateAdapter actualStateAdapter;
    private IoTNodeProvisioner provisioner;
    private DesiredStateDeliveryHandler handler;
    private List<ScenarioBindingEvent>  capturedEvents;


    @BeforeEach
    void setUp() {
        provider = new MockDeviceProvider("test");
        Fixtures.standardHome().forEach(provider::addDevice);
        registry = new MockDeviceRegistry();
        registry.addDevices(provider.discover());

        presetResolver     = mock(IoTPresetResolver.class);
        compiler           = new IoTGoalCompiler();
        actualStateAdapter = mock(IoTActualStateAdapter.class);
        provisioner        = mock(IoTNodeProvisioner.class);

        capturedEvents = new ArrayList<>();

        handler = new DesiredStateDeliveryHandler(
                registry, presetResolver, compiler,
                actualStateAdapter, provisioner, TENANCY_ID, capturedEvents::add);
    }

    @Test
    void name_returns_desired_state() {
        assertThat(handler.name()).isEqualTo("desired-state");
    }

    @Test
    void inline_config_provisions_devices() {
        when(actualStateAdapter.readActual(any(), eq(TENANCY_ID)))
                .thenReturn(new ActualState(Map.of()));
        when(provisioner.provision(any(), any()))
                .thenReturn(new ProvisionResult.Success());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("light-living-1", Map.of("on", false));
        data.put("thermostat-living-1", Map.of("mode", "cool", "target", 22));

        StepOutcome outcome = handler.execute("night-mode", data, CTX);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.result()).containsKey("provisioned");
    }

    @Test
    void preset_reference_resolves_and_provisions() {
        var goals = new IoTGoals(TENANCY_ID, List.of());
        when(presetResolver.resolve("night-mode")).thenReturn(goals);
        when(actualStateAdapter.readActual(any(), eq(TENANCY_ID)))
                .thenReturn(new ActualState(Map.of()));

        Map<String, Object> data = Map.of("preset", "night-mode");

        StepOutcome outcome = handler.execute("apply-preset", data, CTX);

        assertThat(outcome.success()).isTrue();
        verify(presetResolver).resolve("night-mode");
    }

    @Test
    void unknown_device_fails_step() {
        Map<String, Object> data = Map.of(
                "light-living-1", Map.of("on", false),
                "nonexistent-device", Map.of("on", true));

        StepOutcome outcome = handler.execute("bad-step", data, CTX);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.error()).contains("nonexistent-device");
        verify(provisioner, never()).provision(any(), any());
    }

    @Test
    void partial_provision_failure_fails_step() {
        when(actualStateAdapter.readActual(any(), eq(TENANCY_ID)))
                .thenReturn(new ActualState(Map.of()));
        when(provisioner.provision(any(), any()))
                .thenReturn(new ProvisionResult.Success())
                .thenReturn(new ProvisionResult.Failed("provider offline"));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("light-living-1", Map.of("on", false));
        data.put("thermostat-living-1", Map.of("mode", "cool"));

        StepOutcome outcome = handler.execute("partial-fail", data, CTX);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.error()).contains("failed");
    }

    @Test
    void already_converged_returns_success() {
        when(actualStateAdapter.readActual(any(), eq(TENANCY_ID)))
                .thenReturn(new ActualState(Map.of(
                        NodeId.of("light-living-1"), NodeStatus.PRESENT)));
        when(provisioner.provision(any(), any()))
                .thenReturn(new ProvisionResult.AlreadyConverged());

        Map<String, Object> data = Map.of(
                "light-living-1", Map.of("on", false));

        StepOutcome outcome = handler.execute("already-done", data, CTX);

        assertThat(outcome.success()).isTrue();
    }

    @Test
    void preset_not_found_fails_step() {
        when(presetResolver.resolve("missing"))
                .thenThrow(new IllegalArgumentException("Preset not found: missing"));

        Map<String, Object> data = Map.of("preset", "missing");

        StepOutcome outcome = handler.execute("bad-preset", data, CTX);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.error()).contains("Preset not found");
    }

    @Test
    void emitsBindingEventsForSuccessfulReconciliation() {
        when(actualStateAdapter.readActual(any(), eq(TENANCY_ID)))
                .thenReturn(new ActualState(Map.of()));
        when(provisioner.provision(any(), any()))
                .thenReturn(new ProvisionResult.Success());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("light-living-1", Map.of("on", false));
        data.put("thermostat-living-1", Map.of("mode", "cool", "target", 22));

        handler.execute("night-mode", data, CTX);

        assertThat(capturedEvents).isNotEmpty();

        assertThat(capturedEvents.get(0)).isInstanceOf(ScenarioBindingEvent.StepStart.class);
        var start = (ScenarioBindingEvent.StepStart) capturedEvents.get(0);
        assertThat(start.deviceIds()).containsExactlyInAnyOrder("light-living-1", "thermostat-living-1");
        assertThat(start.stepName()).isEqualTo("night-mode");
        assertThat(start.tenancyId()).isEqualTo(TENANCY_ID);

        long provisionedCount = capturedEvents.stream()
                                              .filter(e -> e instanceof ScenarioBindingEvent.DeviceProvisioned)
                                              .count();
        assertThat(provisionedCount).isEqualTo(2);

        var last = capturedEvents.get(capturedEvents.size() - 1);
        assertThat(last).isInstanceOf(ScenarioBindingEvent.StepComplete.class);
        var complete = (ScenarioBindingEvent.StepComplete) last;
        assertThat(complete.provisioned()).isEqualTo(2);
        assertThat(complete.failed()).isZero();
    }

    @Test
    void emitsClearOnReconciliationException() {
        when(actualStateAdapter.readActual(any(), eq(TENANCY_ID)))
                .thenReturn(new ActualState(Map.of()));
        when(provisioner.provision(any(), any()))
                .thenThrow(new RuntimeException("provider crash"));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("light-living-1", Map.of("on", false));

        StepOutcome outcome = handler.execute("crash-step", data, CTX);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.error()).contains("provider crash");

        assertThat(capturedEvents.stream()
                                 .anyMatch(e -> e instanceof ScenarioBindingEvent.StepStart)).isTrue();
        assertThat(capturedEvents.stream()
                                 .anyMatch(e -> e instanceof ScenarioBindingEvent.Clear)).isTrue();
    }


}
