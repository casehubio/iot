package io.casehub.iot.scenario;

import io.casehub.iot.testing.Fixtures;
import io.casehub.iot.testing.MockDeviceProvider;
import io.casehub.iot.testing.MockDeviceRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IoTDeviceVariableSourceTest {

    private IoTDeviceVariableSource source;

    @BeforeEach
    void setUp() {
        var provider = new MockDeviceProvider("test");
        Fixtures.standardHome().forEach(provider::addDevice);
        var registry = new MockDeviceRegistry();
        registry.addDevices(provider.discover());
        source = new IoTDeviceVariableSource(registry);
    }

    @Test
    void prefix_is_device() {
        assertThat(source.prefix()).isEqualTo("device");
    }

    @Test
    void resolve_device_returns_map() {
        Object result = source.resolve("light-living-1");
        assertThat(result).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        var map = (Map<String, Object>) result;
        assertThat(map.get("deviceId")).isEqualTo("light-living-1");
        assertThat(map.get("deviceClass")).isEqualTo("LIGHT");
    }

    @Test
    void resolve_capabilities_returns_map() {
        Object result = source.resolve("light-living-1.capabilities");
        assertThat(result).isInstanceOf(Map.class);
    }

    @Test
    void resolve_nested_capability_returns_value() {
        Object result = source.resolve("light-living-1.capabilities.isOn");
        assertThat(result).isNotNull();
    }

    @Test
    void resolve_device_class_returns_string() {
        Object result = source.resolve("light-living-1.deviceClass");
        assertThat(result).isEqualTo("LIGHT");
    }

    @Test
    void resolve_provider_id_returns_string() {
        Object result = source.resolve("light-living-1.providerId");
        assertThat(result).isEqualTo("test");
    }

    @Test
    void resolve_unknown_device_returns_null() {
        assertThat(source.resolve("nonexistent")).isNull();
        assertThat(source.resolve("nonexistent.capabilities")).isNull();
    }

    @Test
    void resolve_unknown_path_returns_null() {
        assertThat(source.resolve("light-living-1.unknownField")).isNull();
    }
}
