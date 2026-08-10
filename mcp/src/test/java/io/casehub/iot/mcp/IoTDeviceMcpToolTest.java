package io.casehub.iot.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.casehub.iot.api.CommandResult;
import io.casehub.iot.api.DeviceClass;
import io.casehub.iot.api.IoTCommandAuditEvent;
import io.casehub.iot.api.LightDevice;
import io.casehub.iot.api.Temperature;
import io.casehub.iot.api.ThermostatDevice;
import io.casehub.iot.api.ThermostatMode;
import io.casehub.iot.api.spi.DeviceProvider;
import io.casehub.iot.api.spi.DeviceStateHistoryProvider;
import io.casehub.iot.testing.MockDeviceProvider;
import io.casehub.iot.testing.MockDeviceRegistry;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IoTDeviceMcpToolTest {

    private static final Instant NOW = Instant.parse("2026-07-26T10:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private MockDeviceRegistry registry;
    private MockDeviceProvider provider;
    private Instance<DeviceProvider> providers;
    private Event<IoTCommandAuditEvent> auditEvents;
    private Instance<DeviceStateHistoryProvider> historyProviders;
    private McpIdentityContext identityContext;
    private IoTDeviceMcpTool tool;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        registry = new MockDeviceRegistry();
        provider = new MockDeviceProvider("test-provider");
        providers = mock(Instance.class);
        auditEvents = mock(Event.class);
        historyProviders = mock(Instance.class);
        when(providers.stream()).thenReturn(java.util.stream.Stream.of(provider));
        when(historyProviders.isResolvable()).thenReturn(false);
        identityContext = mock(McpIdentityContext.class);
        when(identityContext.tenancyId()).thenReturn("default-tenant");
        when(identityContext.actorId()).thenReturn("mcp-agent");
        tool = new IoTDeviceMcpTool(registry, providers, MAPPER, auditEvents, historyProviders, identityContext);
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
        when(failingProvider.dispatch(any())).thenThrow(new RuntimeException("connection lost"));
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
        provider.setDispatchResult(CommandResult.FAILED);
        when(providers.stream()).thenReturn(java.util.stream.Stream.of(provider));
        String result = tool.sendCommand("light.living_room", "turn_on", null);
        assertThat(result).contains("result: FAILED");
        assertThat(result).doesNotContain("result=SENT");
    }

    @Test
    void sendCommandReportsTimeoutResult() {
        registry.addDevice(light());
        provider.setDispatchResult(CommandResult.TIMEOUT);
        when(providers.stream()).thenReturn(java.util.stream.Stream.of(provider));
        String result = tool.sendCommand("light.living_room", "turn_on", null);
        assertThat(result).contains("result: TIMEOUT");
    }

    @Test
    void sendCommandFiresAuditEvent() {
        registry.addDevice(light());
        when(providers.stream()).thenReturn(java.util.stream.Stream.of(provider));
        tool.sendCommand("light.living_room", "turn_on", null);

        var captor = org.mockito.ArgumentCaptor.forClass(IoTCommandAuditEvent.class);
        verify(auditEvents).fireAsync(captor.capture());
        IoTCommandAuditEvent event = captor.getValue();
        assertThat(event.deviceId()).isEqualTo("light.living_room");
        assertThat(event.action()).isEqualTo("turn_on");
        assertThat(event.result()).isEqualTo(CommandResult.SENT);
        assertThat(event.dispatchedBy()).isEqualTo("mcp-agent");
        assertThat(event.providerId()).isEqualTo("test-provider");
        assertThat(event.correlationId()).isNotNull();
        assertThat(event.timestamp()).isNotNull();
    }

    // --- iot_get_history ---

    @Test
    void getHistoryReturnsUnavailableWhenNoProvider() {
        String result = tool.getHistory("light.living_room", null, null, null);
        assertThat(result).contains("not available");
    }

    @Test
    void getHistoryReturnsEntries() throws Exception {
        var historyProvider = mock(DeviceStateHistoryProvider.class);
        when(historyProviders.isResolvable()).thenReturn(true);
        when(historyProviders.get()).thenReturn(historyProvider);

        var entry = new DeviceStateHistoryProvider.HistoryEntry(
                "light.living_room", "LIGHT", light(),
                java.util.List.of("isOn", "brightness"), NOW);
        when(historyProvider.findHistory("light.living_room", "default-tenant", null, null, 50))
                .thenReturn(java.util.List.of(entry));

        String result = tool.getHistory("light.living_room", null, null, null);
        var array = MAPPER.readTree(result);
        assertThat(array.isArray()).isTrue();
        assertThat(array).hasSize(1);
        assertThat(array.get(0).get("deviceId").asText()).isEqualTo("light.living_room");
    }

    @Test
    void getHistoryRespectsLimit() {
        var historyProvider = mock(DeviceStateHistoryProvider.class);
        when(historyProviders.isResolvable()).thenReturn(true);
        when(historyProviders.get()).thenReturn(historyProvider);
        when(historyProvider.findHistory("light.living_room", "default-tenant", null, null, 10))
                .thenReturn(java.util.List.of());

        tool.getHistory("light.living_room", null, null, 10);
        verify(historyProvider).findHistory("light.living_room", "default-tenant", null, null, 10);
    }

    @Test
    void getHistoryCapsLimitAt200() {
        var historyProvider = mock(DeviceStateHistoryProvider.class);
        when(historyProviders.isResolvable()).thenReturn(true);
        when(historyProviders.get()).thenReturn(historyProvider);
        when(historyProvider.findHistory("light.living_room", "default-tenant", null, null, 200))
                .thenReturn(java.util.List.of());

        tool.getHistory("light.living_room", null, null, 999);
        verify(historyProvider).findHistory("light.living_room", "default-tenant", null, null, 200);
    }

    @Test
    void getHistoryReturnsNoHistoryMessage() {
        var historyProvider = mock(DeviceStateHistoryProvider.class);
        when(historyProviders.isResolvable()).thenReturn(true);
        when(historyProviders.get()).thenReturn(historyProvider);
        when(historyProvider.findHistory("nonexistent", "default-tenant", null, null, 50))
                .thenReturn(java.util.List.of());

        String result = tool.getHistory("nonexistent", null, null, null);
        assertThat(result).contains("No history found");
    }

    @Test
    void sendCommandFiresAuditEventOnFailure() {
        registry.addDevice(light());
        var failingProvider = mock(DeviceProvider.class);
        when(failingProvider.providerId()).thenReturn("test-provider");
        when(failingProvider.dispatch(any())).thenThrow(new RuntimeException("boom"));
        when(providers.stream()).thenReturn(java.util.stream.Stream.of(failingProvider));
        tool.sendCommand("light.living_room", "turn_on", null);

        var captor = org.mockito.ArgumentCaptor.forClass(IoTCommandAuditEvent.class);
        verify(auditEvents).fireAsync(captor.capture());
        assertThat(captor.getValue().result()).isEqualTo(CommandResult.FAILED);
    }
// --- RBAC and tenancy ---

    @Test
    void getDevicesFiltersByTenancy() throws Exception {
        registry.addDevices(light(), thermostat());
        String   result = tool.getDevices(null, null, null);
        JsonNode array  = MAPPER.readTree(result);
        assertThat(array).hasSize(2);
    }

    @Test
    void getStateRejectsDeviceFromOtherTenant() {
        registry.addDevice(light());
        when(identityContext.tenancyId()).thenReturn("other-tenant");
        String result = tool.getState("light.living_room");
        assertThat(result).isEqualTo("Device not found: light.living_room");
    }

    @Test
    void getStateAllowsSameTenantDevice() throws Exception {
        registry.addDevice(thermostat());
        String   result = tool.getState("thermostat.hallway");
        JsonNode node   = MAPPER.readTree(result);
        assertThat(node.get("deviceId").asText()).isEqualTo("thermostat.hallway");
    }

    @Test
    void sendCommandUsesActorId() {
        registry.addDevice(light());
        when(identityContext.actorId()).thenReturn("test-user");
        when(providers.stream()).thenReturn(java.util.stream.Stream.of(provider));
        tool.sendCommand("light.living_room", "turn_on", null);
        assertThat(provider.dispatchedCommands()).hasSize(1);
        assertThat(provider.dispatchedCommands().get(0).dispatchedBy()).isEqualTo("test-user");
    }

    @Test
    void sendCommandRejectsDeviceFromOtherTenant() {
        registry.addDevice(light());
        when(identityContext.tenancyId()).thenReturn("other-tenant");
        String result = tool.sendCommand("light.living_room", "turn_on", null);
        assertThat(result).isEqualTo("Failed: Device not found: light.living_room");
    }

    @Test
    void getHistoryRejectsDeviceFromOtherTenant() {
        var historyProvider = mock(DeviceStateHistoryProvider.class);
        when(historyProvider.findHistory("light.living_room", "other-tenant", null, null, 50))
                .thenReturn(java.util.List.of());
        when(historyProviders.isResolvable()).thenReturn(true);
        when(historyProviders.get()).thenReturn(historyProvider);
        when(identityContext.tenancyId()).thenReturn("other-tenant");
        registry.addDevice(light());
        String result = tool.getHistory("light.living_room", null, null, null);
        assertThat(result).contains("No history found");
    }

    @Test
    void getHistoryAllowsSameTenantDevice() throws Exception {
        var entry = new DeviceStateHistoryProvider.HistoryEntry(
                "light.living_room", "LIGHT", light(), java.util.List.of("state"), NOW);
        var historyProvider = mock(DeviceStateHistoryProvider.class);
        when(historyProvider.findHistory("light.living_room", "default-tenant", null, null, 50))
                .thenReturn(java.util.List.of(entry));
        when(historyProviders.isResolvable()).thenReturn(true);
        when(historyProviders.get()).thenReturn(historyProvider);
        registry.addDevice(light());
        String result = tool.getHistory("light.living_room", null, null, null);
        assertThat(result).contains("light.living_room");
    }

    @Test
    void getHistoryRejectsInvalidDateFormat() {
        var historyProvider = mock(DeviceStateHistoryProvider.class);
        when(historyProviders.isResolvable()).thenReturn(true);
        when(historyProviders.get()).thenReturn(historyProvider);
        String result = tool.getHistory("light.living_room", "not-a-date", null, null);
        assertThat(result).contains("Failed: Invalid date format");
    }
// --- cross-tenant admin ---

    @Test
    void getDevicesReturnsCrossTenantDevicesForAdmin() throws Exception {
        var otherTenantLight = new LightDevice.Builder()
                                       .deviceId("light.other").deviceClass(DeviceClass.LIGHT)
                                       .label("Other Tenant Light").available(true).lastUpdated(NOW)
                                       .tenancyId("other-tenant").providerId("test-provider")
                                       .on(true).build();
        registry.addDevices(light(), otherTenantLight);
        when(identityContext.isCrossTenantAdmin()).thenReturn(true);

        String   result = tool.getDevices(null, null, null);
        JsonNode array  = MAPPER.readTree(result);
        assertThat(array).hasSize(2);
    }

    @Test
    void getStateAllowsCrossTenantAdminToAccessOtherTenantDevice() throws Exception {
        registry.addDevice(light());
        when(identityContext.tenancyId()).thenReturn("other-tenant");
        when(identityContext.isCrossTenantAdmin()).thenReturn(true);

        String   result = tool.getState("light.living_room");
        JsonNode node   = MAPPER.readTree(result);
        assertThat(node.get("deviceId").asText()).isEqualTo("light.living_room");
    }

    @Test
    void sendCommandAllowsCrossTenantAdminToCommandOtherTenantDevice() {
        registry.addDevice(light());
        when(identityContext.tenancyId()).thenReturn("other-tenant");
        when(identityContext.isCrossTenantAdmin()).thenReturn(true);
        when(providers.stream()).thenReturn(java.util.stream.Stream.of(provider));

        String result = tool.sendCommand("light.living_room", "turn_on", null);
        assertThat(result).contains("result=SENT");
    }

    @Test
    void getHistoryPassesNullTenancyForCrossTenantAdmin() {
        var historyProvider = mock(DeviceStateHistoryProvider.class);
        when(historyProviders.isResolvable()).thenReturn(true);
        when(historyProviders.get()).thenReturn(historyProvider);
        when(identityContext.isCrossTenantAdmin()).thenReturn(true);
        when(historyProvider.findHistory("light.living_room", null, null, null, 50))
                .thenReturn(java.util.List.of());

        tool.getHistory("light.living_room", null, null, null);
        verify(historyProvider).findHistory("light.living_room", null, null, null, 50);
    }


}
