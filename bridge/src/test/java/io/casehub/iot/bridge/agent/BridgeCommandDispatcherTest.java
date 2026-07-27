package io.casehub.iot.bridge.agent;

import io.casehub.iot.api.CommandResult;
import io.casehub.iot.api.DeviceClass;
import io.casehub.iot.api.DeviceCommand;
import io.casehub.iot.api.LightDevice;
import io.casehub.iot.api.SwitchDevice;
import io.casehub.iot.testing.Fixtures;
import io.casehub.iot.testing.MockDeviceProvider;
import io.casehub.iot.testing.MockDeviceRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BridgeCommandDispatcherTest {

    @Test
    void dispatchRoutesToCorrectProvider() {
        var haProvider = new MockDeviceProvider("homeassistant");
        haProvider.setDispatchResult(CommandResult.SENT);
        var sw = SwitchDevice.builder()
                .deviceId("switch-1").deviceClass(DeviceClass.SWITCH).label("Switch")
                .available(true).lastUpdated(Fixtures.EPOCH).tenancyId("t1")
                .providerId("homeassistant").on(false).build();
        haProvider.addDevice(sw);

        var ohProvider = new MockDeviceProvider("openhab");
        ohProvider.setDispatchResult(CommandResult.SENT);
        var light = new LightDevice.Builder()
                .deviceId("light-1").deviceClass(DeviceClass.LIGHT).label("Light")
                .available(true).lastUpdated(Fixtures.EPOCH).tenancyId("t1")
                .providerId("openhab").on(false).build();
        ohProvider.addDevice(light);

        var registry = new MockDeviceRegistry();
        registry.addDevice(sw);
        registry.addDevice(light);

        var dispatcher = new BridgeCommandDispatcher(List.of(haProvider, ohProvider), registry);

        var cmd = new DeviceCommand("light-1", "turn_on", Map.of(), "cloud", "corr-1");
        CommandResult result = dispatcher.dispatch(cmd);

        assertThat(result).isEqualTo(CommandResult.SENT);
        assertThat(ohProvider.dispatchedCommands()).hasSize(1);
        assertThat(haProvider.dispatchedCommands()).isEmpty();
    }

    @Test
    void dispatchFailsForUnknownDevice() {
        var provider = new MockDeviceProvider("homeassistant");
        var registry = new MockDeviceRegistry();

        var dispatcher = new BridgeCommandDispatcher(List.of(provider), registry);

        var cmd = new DeviceCommand("unknown-device", "turn_on", Map.of(), "cloud", "corr-1");
        CommandResult result = dispatcher.dispatch(cmd);

        assertThat(result).isEqualTo(CommandResult.FAILED);
        assertThat(provider.dispatchedCommands()).isEmpty();
    }

    @Test
    void dispatchFailsForUnknownProvider() {
        var provider = new MockDeviceProvider("homeassistant");
        var registry = new MockDeviceRegistry();
        registry.addDevice(Fixtures.hallwaySwitch().toBuilder().providerId("unknown-provider").build());

        var dispatcher = new BridgeCommandDispatcher(List.of(provider), registry);

        var cmd = new DeviceCommand("switch-hallway-1", "turn_on", Map.of(), "cloud", "corr-1");
        CommandResult result = dispatcher.dispatch(cmd);

        assertThat(result).isEqualTo(CommandResult.FAILED);
    }

    @Test
    void dispatchFailsWhenNoProviders() {
        var registry = new MockDeviceRegistry();
        var dispatcher = new BridgeCommandDispatcher(List.of(), registry);

        var cmd = new DeviceCommand("switch-1", "turn_on", Map.of(), "cloud", "corr-1");
        CommandResult result = dispatcher.dispatch(cmd);

        assertThat(result).isEqualTo(CommandResult.FAILED);
    }
}
