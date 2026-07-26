package io.casehub.iot.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.casehub.iot.api.DeviceClass;
import io.casehub.iot.api.LightDevice;
import io.casehub.iot.api.Temperature;
import io.casehub.iot.api.ThermostatDevice;
import io.casehub.iot.api.ThermostatMode;
import io.casehub.iot.api.spi.DeviceProvider;
import io.casehub.iot.testing.MockDeviceProvider;
import io.casehub.iot.testing.MockDeviceRegistry;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IoTDeviceMcpToolTest {

    private static final Instant NOW = Instant.parse("2026-07-26T10:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private MockDeviceRegistry registry;
    private MockDeviceProvider provider;
    private Instance<DeviceProvider> providers;
    private IoTDeviceMcpTool tool;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        registry = new MockDeviceRegistry();
        provider = new MockDeviceProvider("test-provider");
        providers = mock(Instance.class);
        when(providers.stream()).thenReturn(java.util.stream.Stream.of(provider));
        tool = new IoTDeviceMcpTool(registry, providers, MAPPER);
    }

    private LightDevice light() {
        return new LightDevice.Builder()
                .deviceId("light.living_room").deviceClass(DeviceClass.LIGHT)
                .label("Living Room Light").available(true).lastUpdated(NOW)
                .tenancyId("default-tenant").providerId("test-provider")
                .on(true).brightness(80).build();
    }

    private ThermostatDevice thermostat() {
        return new ThermostatDevice.Builder()
                .deviceId("thermostat.hallway").deviceClass(DeviceClass.THERMOSTAT)
                .label("Hallway Thermostat").available(true).lastUpdated(NOW)
                .tenancyId("default-tenant").providerId("test-provider")
                .currentTemperature(new Temperature(new BigDecimal("21.5"), Temperature.TemperatureUnit.CELSIUS))
                .targetTemperature(new Temperature(new BigDecimal("22"), Temperature.TemperatureUnit.CELSIUS))
                .mode(ThermostatMode.HEAT).build();
    }

    // --- iot_get_devices ---

    @Test
    void getDevicesReturnsAllDevices() throws Exception {
        registry.addDevices(light(), thermostat());
        String result = tool.getDevices(null, null, null);
        JsonNode array = MAPPER.readTree(result);
        assertThat(array.isArray()).isTrue();
        assertThat(array).hasSize(2);
    }

    @Test
    void getDevicesFiltersByDeviceClass() throws Exception {
        registry.addDevices(light(), thermostat());
        String result = tool.getDevices("LIGHT", null, null);
        JsonNode array = MAPPER.readTree(result);
        assertThat(array).hasSize(1);
        assertThat(array.get(0).get("deviceClass").asText()).isEqualTo("LIGHT");
    }

    @Test
    void getDevicesMatchesDeviceClassCaseInsensitively() throws Exception {
        registry.addDevices(light());
        String result = tool.getDevices("light", null, null);
        JsonNode array = MAPPER.readTree(result);
        assertThat(array).hasSize(1);
    }

    @Test
    void getDevicesReturnsErrorForInvalidDeviceClass() {
        String result = tool.getDevices("INVALID_CLASS", null, null);
        assertThat(result).startsWith("Failed: Unknown device class: INVALID_CLASS");
        assertThat(result).contains("LIGHT");
    }

    @Test
    void getDevicesFiltersByProviderId() throws Exception {
        registry.addDevices(light(), thermostat());
        String result = tool.getDevices(null, "test-provider", null);
        JsonNode array = MAPPER.readTree(result);
        assertThat(array).hasSize(2);

        String result2 = tool.getDevices(null, "unknown-provider", null);
        JsonNode array2 = MAPPER.readTree(result2);
        assertThat(array2).hasSize(0);
    }

    @Test
    void getDevicesFiltersByAvailability() throws Exception {
        var unavailableLight = new LightDevice.Builder()
                .deviceId("light.off").deviceClass(DeviceClass.LIGHT)
                .label("Offline Light").available(false).lastUpdated(NOW)
                .tenancyId("default-tenant").providerId("test-provider")
                .on(false).build();
        registry.addDevices(light(), unavailableLight);

        String onlineResult = tool.getDevices(null, null, true);
        assertThat(MAPPER.readTree(onlineResult)).hasSize(1);

        String offlineResult = tool.getDevices(null, null, false);
        assertThat(MAPPER.readTree(offlineResult)).hasSize(1);
    }

    @Test
    void getDevicesReturnsEmptyArrayWhenNoneMatch() throws Exception {
        String result = tool.getDevices(null, null, null);
        JsonNode array = MAPPER.readTree(result);
        assertThat(array.isArray()).isTrue();
        assertThat(array).isEmpty();
    }

    @Test
    void getDevicesReturnsSummaryFormat() throws Exception {
        registry.addDevice(light());
        String result = tool.getDevices(null, null, null);
        JsonNode device = MAPPER.readTree(result).get(0);
        assertThat(device.has("deviceId")).isTrue();
        assertThat(device.has("deviceClass")).isTrue();
        assertThat(device.has("label")).isTrue();
        assertThat(device.has("providerId")).isTrue();
        assertThat(device.has("available")).isTrue();
        assertThat(device.has("lastUpdated")).isTrue();
        assertThat(device.has("on")).isFalse();
        assertThat(device.has("brightness")).isFalse();
        assertThat(device.has("@deviceType")).isFalse();
    }

    // --- iot_get_state ---

    @Test
    void getStateReturnsDeviceJson() throws Exception {
        registry.addDevice(thermostat());
        String result = tool.getState("thermostat.hallway");
        JsonNode node = MAPPER.readTree(result);
        assertThat(node.get("@deviceType").asText()).isEqualTo("THERMOSTAT:ThermostatDevice");
        assertThat(node.get("deviceId").asText()).isEqualTo("thermostat.hallway");
        assertThat(node.has("currentTemperature")).isTrue();
        assertThat(node.has("mode")).isTrue();
    }

    @Test
    void getStateReturnsErrorForUnknownDevice() {
        String result = tool.getState("nonexistent");
        assertThat(result).isEqualTo("Device not found: nonexistent");
    }

// --- iot_send_command ---

    @Test
    void sendCommandDispatchesToCorrectProvider() {
        registry.addDevice(light());
        when(providers.stream()).thenReturn(java.util.stream.Stream.of(provider));
        String result = tool.sendCommand("light.living_room", "turn_on", null);
        assertThat(result).contains("result=SENT");
        assertThat(provider.dispatchedCommands()).hasSize(1);
        assertThat(provider.dispatchedCommands().get(0).action()).isEqualTo("turn_on");
        assertThat(provider.dispatchedCommands().get(0).targetDeviceId()).isEqualTo("light.living_room");
    }

    @Test
    void sendCommandReturnsConfirmation() {
        registry.addDevice(light());
        when(providers.stream()).thenReturn(java.util.stream.Stream.of(provider));
        String result = tool.sendCommand("light.living_room", "turn_on", null);
        assertThat(result).startsWith("Command turn_on sent to light.living_room");
        assertThat(result).contains("result=SENT");
        assertThat(result).contains("correlationId=");
    }

    @Test
    void sendCommandFailsForUnknownDevice() {
        String result = tool.sendCommand("nonexistent", "turn_on", null);
        assertThat(result).isEqualTo("Failed: Device not found: nonexistent");
    }

    @Test
    void sendCommandFailsForUnknownProvider() {
        var deviceWithUnknownProvider = new LightDevice.Builder()
                                                .deviceId("light.orphan").deviceClass(DeviceClass.LIGHT)
                                                .label("Orphan Light").available(true).lastUpdated(NOW)
                                                .tenancyId("default-tenant").providerId("unknown-provider")
                                                .on(false).build();
        registry.addDevice(deviceWithUnknownProvider);
        when(providers.stream()).thenReturn(java.util.stream.Stream.of(provider));
        String result = tool.sendCommand("light.orphan", "turn_on", null);
        assertThat(result).isEqualTo("Failed: Provider not found: unknown-provider");
    }

    @Test
    void sendCommandHandlesDispatchFailure() {
        registry.addDevice(light());
        var failingProvider = mock(DeviceProvider.class);
        when(failingProvider.providerId()).thenReturn("test-provider");
        when(failingProvider.dispatch(any())).thenReturn(
                io.smallrye.mutiny.Uni.createFrom().failure(new RuntimeException("connection lost")));
        when(providers.stream()).thenReturn(java.util.stream.Stream.of(failingProvider));
        String result = tool.sendCommand("light.living_room", "turn_on", null);
        assertThat(result).startsWith("Failed: ");
        assertThat(result).contains("connection lost");
    }

    @Test
    void sendCommandPassesParametersMap() {
        registry.addDevice(light());
        when(providers.stream()).thenReturn(java.util.stream.Stream.of(provider));
        var params = java.util.Map.<String, Object>of("brightness", 50);
        tool.sendCommand("light.living_room", "turn_on", params);
        assertThat(provider.dispatchedCommands().get(0).parameters())
                .containsEntry("brightness", 50);
    }

    @Test
    void sendCommandHandlesNullParameters() {
        registry.addDevice(light());
        when(providers.stream()).thenReturn(java.util.stream.Stream.of(provider));
        tool.sendCommand("light.living_room", "turn_on", null);
        assertThat(provider.dispatchedCommands().get(0).parameters()).isEmpty();
    }

    @Test
    void sendCommandReportsFailedResult() {
        registry.addDevice(light());
        provider.setDispatchResult(io.casehub.iot.api.CommandResult.FAILED);
        when(providers.stream()).thenReturn(java.util.stream.Stream.of(provider));
        String result = tool.sendCommand("light.living_room", "turn_on", null);
        assertThat(result).contains("result: FAILED");
        assertThat(result).doesNotContain("result=SENT");
    }

    @Test
    void sendCommandReportsTimeoutResult() {
        registry.addDevice(light());
        provider.setDispatchResult(io.casehub.iot.api.CommandResult.TIMEOUT);
        when(providers.stream()).thenReturn(java.util.stream.Stream.of(provider));
        String result = tool.sendCommand("light.living_room", "turn_on", null);
        assertThat(result).contains("result: TIMEOUT");
    }
}
