package io.casehub.iot.scenario;

import io.casehub.iot.api.CommandResult;
import io.casehub.iot.testing.Fixtures;
import io.casehub.iot.testing.MockDeviceProvider;
import io.casehub.iot.testing.MockDeviceRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeviceCommandDispatcherTest {

    private MockDeviceProvider provider;
    private DeviceCommandDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        provider = new MockDeviceProvider("test");
        Fixtures.standardHome().forEach(provider::addDevice);
        var registry = new MockDeviceRegistry();
        registry.addDevices(provider.discover());
        dispatcher = new DeviceCommandDispatcher(registry, List.of(provider));
    }

    @Test
    void dispatch_sent_returns_sent() {
        provider.setDispatchResult(CommandResult.SENT);
        var result = dispatcher.dispatch("light-living-1", "turn_on", Map.of(), "corr-1");
        assertThat(result).isEqualTo(CommandResult.SENT);
        assertThat(provider.dispatchedCommands()).hasSize(1);
        assertThat(provider.dispatchedCommands().get(0).action()).isEqualTo("turn_on");
    }

    @Test
    void dispatch_failed_returns_failed() {
        provider.setDispatchResult(CommandResult.FAILED);
        var result = dispatcher.dispatch("light-living-1", "turn_on", Map.of(), "corr-1");
        assertThat(result).isEqualTo(CommandResult.FAILED);
    }

    @Test
    void dispatch_timeout_returns_timeout() {
        provider.setDispatchResult(CommandResult.TIMEOUT);
        var result = dispatcher.dispatch("light-living-1", "turn_on", Map.of(), "corr-1");
        assertThat(result).isEqualTo(CommandResult.TIMEOUT);
    }

    @Test
    void dispatch_unknown_device_throws() {
        assertThatThrownBy(() -> dispatcher.dispatch("nonexistent", "turn_on", Map.of(), "corr-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Device not found");
    }

    @Test
    void dispatch_unknown_provider_throws() {
        var registry = new MockDeviceRegistry();
        Fixtures.standardHome().forEach(registry::addDevice);
        var emptyDispatcher = new DeviceCommandDispatcher(registry, List.of());

        assertThatThrownBy(() -> emptyDispatcher.dispatch("light-living-1", "turn_on", Map.of(), "corr-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No provider for");
    }

    @Test
    void findDevice_returns_entity() {
        assertThat(dispatcher.findDevice("light-living-1")).isPresent();
        assertThat(dispatcher.findDevice("nonexistent")).isEmpty();
    }
}
