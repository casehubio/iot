package io.casehub.iot.desiredstate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IoTPresetResolverTest {

    private IoTPresetResolver resolver;
    private String presetDir;

    @BeforeEach
    void setUp() throws URISyntaxException {
        var dirUrl = getClass().getClassLoader().getResource("presets");
        presetDir = Path.of(dirUrl.toURI()).toString();
        var loader = new IoTGoalLoader();
        resolver = new IoTPresetResolver(loader, presetDir);
    }

    @Test
    void resolve_standalonePreset_loadsDirectly() {
        IoTGoals goals = resolver.resolve("standalone");
        assertThat(goals.devices()).hasSize(1);
        assertThat(goals.devices().getFirst().deviceId()).isEqualTo("switch-hall");
    }

    @Test
    void resolve_presetWithImports_mergesInOrder() {
        IoTGoals goals = resolver.resolve("away-mode");
        assertThat(goals.tenancyId()).isEqualTo("test-tenant");
        var deviceIds = goals.devices().stream().map(IoTDeviceGoal::deviceId).toList();
        assertThat(deviceIds).containsExactlyInAnyOrder("light-living", "lock-front", "camera-front");
    }

    @Test
    void resolve_presetWithImports_lastWinsForOverlappingDevice() {
        IoTGoals goals = resolver.resolve("away-mode");
        var light = goals.devices().stream()
            .filter(d -> d.deviceId().equals("light-living")).findFirst().orElseThrow();
        assertThat(light.config()).containsEntry("brightness", 0);
        assertThat(light.config()).containsEntry("isOn", true);
    }

    @Test
    void resolve_missingPreset_throwsClearError() {
        assertThatThrownBy(() -> resolver.resolve("nonexistent"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("nonexistent");
    }

    @Test
    void resolve_missingImport_throwsClearError() throws IOException {
        Path tempDir = Files.createTempDirectory("presets");
        Files.writeString(tempDir.resolve("broken.yaml"),
            "import:\n  - does-not-exist\ntenancyId: t1\ndevices: []");
        var tempResolver = new IoTPresetResolver(new IoTGoalLoader(), tempDir.toString());
        assertThatThrownBy(() -> tempResolver.resolve("broken"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does-not-exist");
    }

    @Test
    void listPresets_returnsAllPresetsWithMetadata() {
        List<PresetInfo> presets = resolver.listPresets();
        assertThat(presets).hasSizeGreaterThanOrEqualTo(4);
        var awayMode = presets.stream().filter(p -> p.name().equals("away-mode")).findFirst().orElseThrow();
        assertThat(awayMode.imports()).containsExactly("night-mode", "locks-armed");
        assertThat(awayMode.deviceCount()).isEqualTo(2);
    }

    @Test
    void listPresets_standaloneHasNoImports() {
        List<PresetInfo> presets = resolver.listPresets();
        var standalone = presets.stream().filter(p -> p.name().equals("standalone")).findFirst().orElseThrow();
        assertThat(standalone.imports()).isEmpty();
        assertThat(standalone.deviceCount()).isEqualTo(1);
    }
}
