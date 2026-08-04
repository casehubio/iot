package io.casehub.iot.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.iot.api.CommandResult;
import io.casehub.iot.api.DeviceClass;
import io.casehub.iot.api.DeviceCommand;
import io.casehub.iot.api.IoTCommandAuditEvent;
import io.casehub.iot.api.IoTRoles;
import io.casehub.iot.api.spi.DeviceProvider;
import io.casehub.iot.api.spi.DeviceRegistry;
import io.casehub.iot.api.spi.DeviceStateHistoryProvider;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.smallrye.common.annotation.Blocking;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class IoTDeviceMcpTool {

    private static final Logger LOG = Logger.getLogger(IoTDeviceMcpTool.class);

    private final DeviceRegistry                       deviceRegistry;
    private final Instance<DeviceProvider>             providers;
    private final ObjectMapper                         objectMapper;
    private final Event<IoTCommandAuditEvent>          auditEvents;
    private final Instance<DeviceStateHistoryProvider> historyProviders;
    private final McpIdentityContext                   identityContext;


    @Inject
    public IoTDeviceMcpTool(final DeviceRegistry deviceRegistry,
                            final Instance<DeviceProvider> providers,
                            final ObjectMapper objectMapper,
                            final Event<IoTCommandAuditEvent> auditEvents,
                            final Instance<DeviceStateHistoryProvider> historyProviders, McpIdentityContext identityContext) {
        this.deviceRegistry   = deviceRegistry;
        this.providers        = providers;
        this.objectMapper     = objectMapper;
        this.auditEvents      = auditEvents;
        this.historyProviders = historyProviders;
        this.identityContext  = identityContext;
    }

    @Tool(name = "iot_get_devices",
          description = "List IoT devices with optional filters. Returns device ID, "
                        + "class, label, location, provider, and availability. "
                        + "Use iot_get_state for full device state.")
    @Blocking
    @RolesAllowed(IoTRoles.VIEWER)
    public String getDevices(
            @ToolArg(description = "Filter by device class. Valid values: SWITCH, LIGHT, "
                                   + "THERMOSTAT, SENSOR, PRESENCE_SENSOR, POWER_SENSOR, "
                                   + "LOCK, COVER, MEDIA_PLAYER, FAN, CAMERA. Case-insensitive.",
                     required = false) final String deviceClass,
            @ToolArg(description = "Filter by provider ID (e.g. 'homeassistant', 'openhab').",
                     required = false) final String providerId,
            @ToolArg(description = "Filter by availability: true for online devices, "
                                   + "false for offline.",
                     required = false) final Boolean available) {

        final DeviceClass parsedClass;
        if (deviceClass != null && !deviceClass.isBlank()) {
            try {
                parsedClass = DeviceClass.valueOf(deviceClass.toUpperCase());
            } catch (final IllegalArgumentException e) {
                return "Failed: Unknown device class: " + deviceClass
                       + ". Valid values: " + Arrays.stream(DeviceClass.values())
                                                    .map(Enum::name)
                                                    .collect(Collectors.joining(", "));
            }
        } else {
            parsedClass = null;
        }

        List<DeviceSummary> summaries = deviceRegistry.findByTenancyId(identityContext.tenancyId()).stream()
                                                      .filter(d -> parsedClass == null || d.deviceClass() == parsedClass)
                                                      .filter(d -> providerId == null || d.providerId().equals(providerId))
                                                      .filter(d -> available == null || d.available() == available)
                                                      .map(DeviceSummary::from)
                                                      .toList();

        try {
            return objectMapper.writeValueAsString(summaries);
        } catch (final JsonProcessingException e) {
            LOG.warnf("iot_get_devices failed [%s]: %s",
                      e.getClass().getSimpleName(), e.getMessage());
            return "Failed: " + e.getMessage();
        }
    }

    @Tool(name = "iot_get_state",
          description = "Get current state for a specific IoT device. Returns the full "
                        + "device state including typed fields (temperature, humidity, "
                        + "mode, etc.) and availability.")
    @Blocking
    @RolesAllowed(IoTRoles.VIEWER)
    public String getState(
            @ToolArg(description = "The device ID to query (e.g. 'light.living_room', "
                                   + "'sensor.outdoor_temp'). Use iot_get_devices to "
                                   + "discover available device IDs.") final String deviceId) {
        return deviceRegistry.findById(deviceId, identityContext.tenancyId())
                             .map(device -> {
                                 try {
                                     return objectMapper.writeValueAsString(device);
                                 } catch (final JsonProcessingException e) {
                                     LOG.warnf("iot_get_state failed [%s]: %s",
                                               e.getClass().getSimpleName(), e.getMessage());
                                     return "Failed: " + e.getMessage();
                                 }
                             })
                             .orElse("Device not found: " + deviceId);
    }

    @Tool(name = "iot_send_command",
          description = "Send a command to an IoT device. Supports actions: "
                        + "turn_on, turn_off, set_temperature, lock, unlock, "
                        + "set_position, set_volume. Returns confirmation with "
                        + "correlation ID on success.")
    @Blocking
    @RolesAllowed(IoTRoles.OPERATOR)
    public String sendCommand(
            @ToolArg(description = "Target device ID (e.g. 'light.living_room'). "
                                   + "Use iot_get_devices to find available devices.") final String deviceId,
            @ToolArg(description = "Command action: turn_on, turn_off, set_temperature, "
                                   + "lock, unlock, set_position, set_volume.") final String action,
            @ToolArg(description = "Command parameters (e.g. {\"temperature\": 22.0, "
                                   + "\"unit\": \"CELSIUS\"} for set_temperature, "
                                   + "{\"position\": 50} for set_position, "
                                   + "{\"volume\": 75} for set_volume). Not needed for "
                                   + "turn_on, turn_off, lock, unlock.",
                     required = false) final Map<String, Object> parameters) {
        var deviceOpt = deviceRegistry.findById(deviceId, identityContext.tenancyId());
        if (deviceOpt.isEmpty()) {
            LOG.warnf("iot_send_command failed: Device not found: %s", deviceId);
            return "Failed: Device not found: " + deviceId;
        }
        var device = deviceOpt.get();

        var providerOpt = providers.stream()
                                   .filter(p -> p.providerId().equals(device.providerId()))
                                   .findFirst();
        if (providerOpt.isEmpty()) {
            LOG.warnf("iot_send_command failed: Provider not found: %s", device.providerId());
            return "Failed: Provider not found: " + device.providerId();
        }

        String correlationId = UUID.randomUUID().toString();
        var command = new DeviceCommand(
                deviceId,
                action,
                parameters != null ? parameters : Map.of(),
                identityContext.actorId(),
                correlationId
        );

        CommandResult result;
        try {
            result = providerOpt.get().dispatch(command);
        } catch (final Exception e) {
            LOG.warnf("iot_send_command failed [%s]: %s",
                      e.getClass().getSimpleName(), e.getMessage());
            result = CommandResult.FAILED;
            fireAuditEvent(command, result, device.providerId());
            return "Failed: " + e.getMessage();
        }

        fireAuditEvent(command, result, device.providerId());

        if (result == CommandResult.SENT) {
            return "Command " + action + " sent to " + deviceId
                   + " (result=SENT, correlationId=" + correlationId + ")";
        }
        return "Command " + action + " to " + deviceId
               + " result: " + result.name()
               + " (correlationId=" + correlationId + ")";
    }

    @Tool(name = "iot_get_history",
          description = "Get state change history for a device. Returns timestamped "
                        + "state snapshots with changed capabilities. Requires a "
                        + "history provider (available in webapp deployments).")
    @Blocking
    @RolesAllowed(IoTRoles.VIEWER)
    public String getHistory(
            @ToolArg(description = "The device ID to query history for.") final String deviceId,
            @ToolArg(description = "Start time (ISO-8601, e.g. '2026-07-01T00:00:00Z'). "
                                   + "Omit for no lower bound.",
                     required = false) final String from,
            @ToolArg(description = "End time (ISO-8601). Omit for no upper bound.",
                     required = false) final String to,
            @ToolArg(description = "Maximum number of entries to return (default 50, max 200).",
                     required = false) final Integer limit) {
        if (!historyProviders.isResolvable()) {
            return "Failed: Device state history is not available in this deployment.";
        }

        var     historyProvider = historyProviders.get();
        Instant fromInstant;
        Instant toInstant;
        try {
            fromInstant = from != null ? Instant.parse(from) : null;
            toInstant   = to != null ? Instant.parse(to) : null;
        } catch (DateTimeParseException e) {
            return "Failed: Invalid date format. Use ISO-8601 (e.g. '2026-07-01T00:00:00Z').";
        }
        int effectiveLimit = limit != null ? Math.min(limit, 200) : 50;

        var entries = historyProvider.findHistory(deviceId, identityContext.tenancyId(),
                                                  fromInstant, toInstant, effectiveLimit);

        if (entries.isEmpty()) {
            return "No history found for device: " + deviceId;
        }

        try {
            return objectMapper.writeValueAsString(entries);
        } catch (final JsonProcessingException e) {
            LOG.warnf("iot_get_history failed [%s]: %s",
                      e.getClass().getSimpleName(), e.getMessage());
            return "Failed: " + e.getMessage();
        }
    }

    private void fireAuditEvent(DeviceCommand command, CommandResult result, String providerId) {
        auditEvents.fireAsync(new IoTCommandAuditEvent(
                command.targetDeviceId(),
                command.action(),
                command.parameters(),
                result,
                command.dispatchedBy(),
                command.correlationId(),
                providerId,
                identityContext.tenancyId(),
                Instant.now()));
    }
}
