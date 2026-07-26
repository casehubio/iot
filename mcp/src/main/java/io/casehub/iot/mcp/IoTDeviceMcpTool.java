package io.casehub.iot.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.iot.api.CommandResult;
import io.casehub.iot.api.DeviceClass;
import io.casehub.iot.api.DeviceCommand;
import io.casehub.iot.api.spi.DeviceProvider;
import io.casehub.iot.api.spi.DeviceRegistry;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Arrays;
import java.util.Map;
import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
public class IoTDeviceMcpTool {

    private static final Logger LOG = Logger.getLogger(IoTDeviceMcpTool.class);

    private final DeviceRegistry deviceRegistry;
    private final Instance<DeviceProvider> providers;
    private final ObjectMapper objectMapper;

    @Inject
    public IoTDeviceMcpTool(final DeviceRegistry deviceRegistry,
                            final Instance<DeviceProvider> providers,
                            final ObjectMapper objectMapper) {
        this.deviceRegistry = deviceRegistry;
        this.providers = providers;
        this.objectMapper = objectMapper;
    }

    @Tool(name = "iot_get_devices",
          description = "List IoT devices with optional filters. Returns device ID, "
                      + "class, label, location, provider, and availability. "
                      + "Use iot_get_state for full device state.")
    @Blocking
    public String getDevices(
            @ToolArg(description = "Filter by device class. Valid values: SWITCH, LIGHT, "
                                 + "THERMOSTAT, SENSOR, PRESENCE_SENSOR, POWER_SENSOR, "
                                 + "LOCK, COVER, MEDIA_PLAYER, FAN, CAMERA. Case-insensitive.",
                     required = false)
            final String deviceClass,
            @ToolArg(description = "Filter by provider ID (e.g. 'homeassistant', 'openhab').",
                     required = false)
            final String providerId,
            @ToolArg(description = "Filter by availability: true for online devices, "
                                 + "false for offline.",
                     required = false)
            final Boolean available) {

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

        List<DeviceSummary> summaries = deviceRegistry.findAll().stream()
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
    public String getState(
            @ToolArg(description = "The device ID to query (e.g. 'light.living_room', "
                                 + "'sensor.outdoor_temp'). Use iot_get_devices to "
                                 + "discover available device IDs.")
            final String deviceId) {
        return deviceRegistry.findById(deviceId)
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
        var deviceOpt = deviceRegistry.findById(deviceId);
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

        String correlationId = java.util.UUID.randomUUID().toString();
        var command = new DeviceCommand(
                deviceId,
                action,
                parameters != null ? parameters : Map.of(),
                "mcp-agent",
                correlationId
        );

        try {
            var result = providerOpt.get().dispatch(command)
                                    .await().atMost(java.time.Duration.ofSeconds(30));

            if (result == CommandResult.SENT) {
                return "Command " + action + " sent to " + deviceId
                       + " (result=SENT, correlationId=" + correlationId + ")";
            }
            return "Command " + action + " to " + deviceId
                   + " result: " + result.name()
                   + " (correlationId=" + correlationId + ")";
        } catch (final Exception e) {
            LOG.warnf("iot_send_command failed [%s]: %s",
                      e.getClass().getSimpleName(), e.getMessage());
            if (e instanceof io.smallrye.mutiny.TimeoutException) {
                return "Failed: Command timed out after 30s"
                       + " (correlationId=" + correlationId + ")";
            }
            return "Failed: " + e.getMessage();
        }
    }

}
