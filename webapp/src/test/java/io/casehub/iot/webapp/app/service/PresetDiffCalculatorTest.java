package io.casehub.iot.webapp.app.service;

import io.casehub.iot.api.DeviceClass;
import io.casehub.iot.api.SwitchDevice;
import io.casehub.iot.desiredstate.IoTDeviceGoal;
import io.casehub.iot.desiredstate.IoTGoals;
import io.casehub.iot.testing.MockDeviceRegistry;
import io.casehub.iot.webapp.rest.PresetDiff;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PresetDiffCalculatorTest {

    private MockDeviceRegistry registry;
    private PresetDiffCalculator calculator;

    @BeforeEach
    void setUp() {
        registry = new MockDeviceRegistry();
        calculator = new PresetDiffCalculator();
    }

    @Test
    void deviceWithChanges_showsPropertyDiff() {
        registry.addDevice(new SwitchDevice.Builder()
            .deviceId("switch-1").deviceClass(DeviceClass.SWITCH)
            .label("Switch").available(true).lastUpdated(Instant.EPOCH)
            .tenancyId("t1").providerId("test").on(false).build());

        var goals = new IoTGoals("t1", List.of(
            new IoTDeviceGoal("switch-1", DeviceClass.SWITCH, "Switch", true,
                Map.of("isOn", true), List.of())));

        PresetDiff diff = calculator.calculate(goals, registry, "t1");
        assertThat(diff.changes()).hasSize(1);
        assertThat(diff.changes().getFirst().deviceId()).isEqualTo("switch-1");
        var props = diff.changes().getFirst().properties();
        assertThat(props).anyMatch(p -> p.property().equals("isOn")
            && Boolean.FALSE.equals(p.current()) && Boolean.TRUE.equals(p.desired()));
    }

    @Test
    void deviceNotInRegistry_showsNullCurrent() {
        var goals = new IoTGoals("t1", List.of(
            new IoTDeviceGoal("new-device", DeviceClass.SWITCH, "New", true,
                Map.of("isOn", true), List.of())));

        PresetDiff diff = calculator.calculate(goals, registry, "t1");
        assertThat(diff.changes()).hasSize(1);
        var props = diff.changes().getFirst().properties();
        assertThat(props).anyMatch(p -> p.property().equals("isOn") && p.current() == null);
    }

    @Test
    void deviceAlreadyAtDesiredState_excludedFromDiff() {
        registry.addDevice(new SwitchDevice.Builder()
            .deviceId("switch-1").deviceClass(DeviceClass.SWITCH)
            .label("Switch").available(true).lastUpdated(Instant.EPOCH)
            .tenancyId("t1").providerId("test").on(true).build());

        var goals = new IoTGoals("t1", List.of(
            new IoTDeviceGoal("switch-1", DeviceClass.SWITCH, "Switch", true,
                Map.of("isOn", true), List.of())));

        PresetDiff diff = calculator.calculate(goals, registry, "t1");
        assertThat(diff.changes()).isEmpty();
    }
}
