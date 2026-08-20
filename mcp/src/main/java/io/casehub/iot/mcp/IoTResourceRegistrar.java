package io.casehub.iot.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.iot.api.DeviceEntity;
import io.casehub.iot.api.spi.DeviceRegistry;
import io.casehub.platform.api.mcp.McpResourceContent;
import io.casehub.platform.api.mcp.McpResourceDescriptor;
import io.casehub.platform.api.mcp.McpResourceHandle;
import io.casehub.platform.api.mcp.McpResourceReadRequest;
import io.casehub.platform.api.mcp.McpResourceRegistry;
import io.casehub.platform.api.mcp.StaticResourceDescriptor;
import io.casehub.platform.api.mcp.TemplateResourceDescriptor;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

@ApplicationScoped
public class IoTResourceRegistrar {

    private final McpResourceRegistry resourceRegistry;
    private final DeviceRegistry deviceRegistry;
    private final ObjectMapper objectMapper;

    private McpResourceHandle deviceStateHandle;
    private McpResourceHandle changesHandle;

    private final Deque<StateChangeSummary> recentChanges = new ConcurrentLinkedDeque<>();
    private static final int MAX_RECENT = 50;

    @Inject
    public IoTResourceRegistrar(McpResourceRegistry resourceRegistry,
                                 DeviceRegistry deviceRegistry,
                                 ObjectMapper objectMapper) {
        this.resourceRegistry = resourceRegistry;
        this.deviceRegistry = deviceRegistry;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void register() {
        deviceStateHandle = resourceRegistry
            .newResource(McpResourceDescriptor.template(
                "iot-device-state",
                "iot://devices/{deviceId}/state",
                "application/json",
                "Current state of an IoT device including all capabilities")
                .withSubscribable(true))
            .handler(this::readDeviceState)
            .completion("deviceId", this::listDeviceIds)
            .register();

        changesHandle = resourceRegistry
            .newResource(McpResourceDescriptor.of(
                "iot-device-changes",
                "iot://devices/changes",
                "application/json",
                "Recent device state changes across all providers")
                .withSubscribable(true))
            .handler(this::readChanges)
            .register();
    }

    void notifyDeviceUpdate(String deviceId) {
        if (deviceStateHandle != null) {
            deviceStateHandle.notifyUpdate("iot://devices/" + deviceId + "/state");
        }
    }

    void notifyChangesUpdate() {
        if (changesHandle != null) {
            changesHandle.notifyUpdate("iot://devices/changes");
        }
    }

    void addChange(StateChangeSummary summary) {
        recentChanges.addFirst(summary);
        while (recentChanges.size() > MAX_RECENT) {
            recentChanges.removeLast();
        }
    }

    private McpResourceContent readDeviceState(McpResourceReadRequest request) throws JsonProcessingException {
        String deviceId = request.templateArgs().get("deviceId");
        DeviceEntity device = deviceRegistry.findById(deviceId)
            .orElseThrow(() -> new IllegalArgumentException("Device not found: " + deviceId));
        return McpResourceContent.of(request.uri(), objectMapper.writeValueAsString(device));
    }

    private McpResourceContent readChanges(McpResourceReadRequest request) throws JsonProcessingException {
        return McpResourceContent.of(request.uri(), objectMapper.writeValueAsString(List.copyOf(recentChanges)));
    }

    private List<String> listDeviceIds() {
        return deviceRegistry.findAll().stream()
            .map(DeviceEntity::deviceId)
            .toList();
    }
}
