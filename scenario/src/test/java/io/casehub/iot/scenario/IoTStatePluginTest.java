package io.casehub.iot.scenario;

import io.casehub.iot.testing.Fixtures;
import io.casehub.iot.testing.MockDeviceProvider;
import io.casehub.iot.testing.MockDeviceRegistry;
import io.casehub.yaml.plugin.api.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IoTStatePluginTest {

    private MockDeviceRegistry registry;

    @BeforeEach
    void setUp() {
        var provider = new MockDeviceProvider("test");
        Fixtures.standardHome().forEach(provider::addDevice);
        registry = new MockDeviceRegistry();
        registry.addDevices(provider.discover());
    }

    @Test
    void returns_device_state() {
        var plugin = new IoTStatePlugin("light-living-1");
        Result result = plugin.run(registry);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.output().get("deviceId")).isEqualTo("light-living-1");
        assertThat(result.output().get("deviceClass")).isEqualTo("LIGHT");
        assertThat(result.output()).containsKey("capabilities");
        assertThat(result.output().get("providerId")).isEqualTo("test");
    }

    @Test
    void unknown_device_returns_failure() {
        var plugin = new IoTStatePlugin("nonexistent");
        Result result = plugin.run(registry);

        assertThat(result.isSuccess()).isFalse();
        assertThat(((Result.Failure) result).message()).contains("Device not found");
    }
}
