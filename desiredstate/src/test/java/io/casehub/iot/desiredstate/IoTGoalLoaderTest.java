package io.casehub.iot.desiredstate;

import io.casehub.iot.api.DeviceClass;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IoTGoalLoaderTest {

    private IoTGoalLoader loader;

    @BeforeEach
    void setUp() {
        loader = new IoTGoalLoader();
    }

    @Test
    void loadSingleFile() {
        IoTGoals goals = loader.load("iot-topology-simple.yaml");
        assertThat(goals.tenancyId()).isEqualTo("test-tenant");
        assertThat(goals.devices()).hasSize(1);
        assertThat(goals.devices().getFirst().deviceId()).isEqualTo("switch-1");
        assertThat(goals.devices().getFirst().deviceClass()).isEqualTo(DeviceClass.SWITCH);
    }

    @Test
    void loadDirectory() throws URISyntaxException {
        var dirUrl = getClass().getClassLoader().getResource("iot-topology-dir");
        IoTGoals goals = loader.loadDirectory(Path.of(dirUrl.toURI()).toString());
        assertThat(goals.devices()).hasSize(2);
    }

    @Test
    void mergeDuplicateDeviceIdThrows() {
        IoTGoals a = loader.load("iot-topology-simple.yaml");
        IoTGoals b = loader.load("iot-topology-simple.yaml");
        assertThatThrownBy(() -> IoTGoalLoader.merge(a, b))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("switch-1");
    }

    @Test
    void mergeInconsistentTenancyIdThrows() {
        var a = new IoTGoals("tenant-a", List.of(
            new IoTDeviceGoal("dev-1", DeviceClass.SWITCH, "Switch", true, Map.of("isOn", true), List.of())));
        var b = new IoTGoals("tenant-b", List.of(
            new IoTDeviceGoal("dev-2", DeviceClass.LIGHT, "Light", true, Map.of("isOn", false), List.of())));
        assertThatThrownBy(() -> IoTGoalLoader.merge(a, b))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Inconsistent tenancyId");
    }

    @Test
    void mergeGoals_nonOverlapping_combinesDevices() {
        var a = new IoTGoals("t1", List.of(
            new IoTDeviceGoal("dev-1", DeviceClass.SWITCH, "Switch", true, Map.of("isOn", true), List.of())));
        var b = new IoTGoals("t1", List.of(
            new IoTDeviceGoal("dev-2", DeviceClass.LIGHT, "Light", true, Map.of("brightness", 80), List.of())));
        IoTGoals merged = IoTGoalLoader.mergeGoals(a, b);
        assertThat(merged.devices()).hasSize(2);
        assertThat(merged.tenancyId()).isEqualTo("t1");
    }

    @Test
    void mergeGoals_overlappingDeviceId_deepMergesConfig() {
        var a = new IoTGoals("t1", List.of(
            new IoTDeviceGoal("dev-1", DeviceClass.LIGHT, "Light", true, Map.of("isOn", true, "brightness", 30), List.of())));
        var b = new IoTGoals("t1", List.of(
            new IoTDeviceGoal("dev-1", DeviceClass.LIGHT, "Light", true, Map.of("brightness", 80), List.of())));
        IoTGoals merged = IoTGoalLoader.mergeGoals(a, b);
        assertThat(merged.devices()).hasSize(1);
        var config = merged.devices().getFirst().config();
        assertThat(config).containsEntry("isOn", true);
        assertThat(config).containsEntry("brightness", 80);
    }

    @Test
    void mergeGoals_overlappingDeviceId_laterWinsForNonConfigFields() {
        var a = new IoTGoals("t1", List.of(
            new IoTDeviceGoal("dev-1", DeviceClass.LIGHT, "Light A", true, Map.of("isOn", true), List.of())));
        var b = new IoTGoals("t1", List.of(
            new IoTDeviceGoal("dev-1", DeviceClass.LIGHT, "Light B", false, Map.of("brightness", 80), List.of())));
        IoTGoals merged = IoTGoalLoader.mergeGoals(a, b);
        assertThat(merged.devices().getFirst().label()).isEqualTo("Light B");
        assertThat(merged.devices().getFirst().physical()).isFalse();
    }

    @Test
    void mergeGoals_inconsistentTenancyId_throws() {
        var a = new IoTGoals("t1", List.of(
            new IoTDeviceGoal("dev-1", DeviceClass.SWITCH, "Switch", true, Map.of("isOn", true), List.of())));
        var b = new IoTGoals("t2", List.of(
            new IoTDeviceGoal("dev-2", DeviceClass.LIGHT, "Light", true, Map.of("isOn", false), List.of())));
        assertThatThrownBy(() -> IoTGoalLoader.mergeGoals(a, b))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Inconsistent tenancyId");
    }
}
