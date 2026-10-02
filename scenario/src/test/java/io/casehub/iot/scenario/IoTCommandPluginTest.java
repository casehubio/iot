package io.casehub.iot.scenario;

import io.casehub.iot.api.CommandResult;
import io.casehub.iot.testing.Fixtures;
import io.casehub.iot.testing.MockDeviceProvider;
import io.casehub.iot.testing.MockDeviceRegistry;
import io.casehub.yaml.plugin.api.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IoTCommandPluginTest {

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
    void sent_returns_success_with_device_and_action() {
        provider.setDispatchResult(CommandResult.SENT);
        var plugin = new IoTCommandPlugin("light-living-1", "turn_on", null, null);
        Result result = plugin.run(dispatcher);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.output().get("result")).isEqualTo("SENT");
        assertThat(result.output().get("device")).isEqualTo("light-living-1");
        assertThat(result.output().get("action")).isEqualTo("turn_on");
        assertThat(result.output().get("correlationId")).isNotNull();
    }

    @Test
    void failed_returns_failure() {
        provider.setDispatchResult(CommandResult.FAILED);
        var plugin = new IoTCommandPlugin("light-living-1", "turn_on", null, null);
        Result result = plugin.run(dispatcher);

        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    void timeout_returns_failure() {
        provider.setDispatchResult(CommandResult.TIMEOUT);
        var plugin = new IoTCommandPlugin("light-living-1", "turn_on", null, null);
        Result result = plugin.run(dispatcher);

        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    void unknown_device_returns_failure() {
        var plugin = new IoTCommandPlugin("nonexistent", "turn_on", null, null);
        Result result = plugin.run(dispatcher);

        assertThat(result.isSuccess()).isFalse();
        assertThat(((Result.Failure) result).message()).contains("Device not found");
    }

    @Test
    void custom_correlation_id_is_used() {
        provider.setDispatchResult(CommandResult.SENT);
        var plugin = new IoTCommandPlugin("light-living-1", "turn_on", null, "my-id");
        Result result = plugin.run(dispatcher);

        assertThat(result.output().get("correlationId")).isEqualTo("my-id");
        assertThat(provider.dispatchedCommands().get(0).correlationId()).isEqualTo("my-id");
    }

    @Test
    void params_forwarded_to_command() {
        provider.setDispatchResult(CommandResult.SENT);
        var params = Map.<String, Object>of("temperature", 22, "unit", "CELSIUS");
        var plugin = new IoTCommandPlugin("thermostat-living-1", "set_temperature", params, null);
        plugin.run(dispatcher);

        assertThat(provider.dispatchedCommands().get(0).parameters()).containsEntry("temperature", 22);
    }
}
