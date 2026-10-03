package io.casehub.iot.webapp.app.service;

import io.casehub.iot.api.DeviceClass;
import io.casehub.iot.api.SwitchDevice;
import io.casehub.iot.desiredstate.IoTGoalLoader;
import io.casehub.iot.desiredstate.IoTPresetResolver;
import io.casehub.iot.desiredstate.PresetInfo;
import io.casehub.iot.testing.MockDeviceRegistry;
import io.casehub.iot.webapp.rest.PresetDiff;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultIoTPresetApiTest {

    private DefaultIoTPresetApi api;
    private MockDeviceRegistry registry;

    @BeforeEach
    void setUp() throws URISyntaxException {
        var dirUrl = getClass().getClassLoader().getResource("presets");
        String presetDir = Path.of(dirUrl.toURI()).toString();
        var loader = new IoTGoalLoader();
        var resolver = new IoTPresetResolver(loader, presetDir);
        registry = new MockDeviceRegistry();
        var diffCalculator = new PresetDiffCalculator();

        api = new DefaultIoTPresetApi();
        api.resolver = resolver;
        api.deviceRegistry = registry;
        api.diffCalculator = diffCalculator;
    }

    @Test
    void listPresets_returnsAllPresets() {
        List<PresetInfo> presets = api.listPresets("t1");
        assertThat(presets).isNotEmpty();
        assertThat(presets.stream().map(PresetInfo::name))
            .contains("night-mode", "locks-armed", "away-mode", "standalone");
    }

    @Test
    void diffPreset_showsExpectedChanges() {
        registry.addDevice(new SwitchDevice.Builder()
            .deviceId("switch-hall").deviceClass(DeviceClass.SWITCH)
            .label("Switch").available(true).lastUpdated(Instant.EPOCH)
            .tenancyId("test-tenant").providerId("test").on(true).build());

        PresetDiff diff = api.diffPreset("test-tenant", "standalone");
        assertThat(diff.changes()).hasSize(1);
        assertThat(diff.changes().getFirst().deviceId()).isEqualTo("switch-hall");
    }

    @Test
    void diffPreset_newDevice_showsAllPropertiesAsNew() {
        PresetDiff diff = api.diffPreset("test-tenant", "standalone");
        assertThat(diff.changes()).hasSize(1);
        var props = diff.changes().getFirst().properties();
        assertThat(props).anyMatch(p -> p.property().equals("isOn") && p.current() == null);
    }
}
