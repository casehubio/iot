package io.casehub.iot.webapp.app.service;

import io.casehub.iot.api.DeviceCommand;
import io.casehub.iot.api.DeviceEntity;
import io.casehub.iot.api.StateChangeEvent;
import io.casehub.iot.api.spi.DeviceProvider;
import io.casehub.iot.api.spi.DeviceRegistry;
import io.casehub.iot.api.spi.DeviceStateHistoryProvider;
import io.casehub.iot.webapp.rest.CommandRequest;
import io.casehub.iot.webapp.rest.CommandResponse;
import io.casehub.iot.webapp.rest.DeviceResponse;
import io.casehub.iot.webapp.view.StateHistoryView;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.api.mcp.ContextParam;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PathParam;
import io.casehub.platform.api.mcp.PlatformMutation;
import io.casehub.platform.api.mcp.PlatformQuery;
import io.casehub.platform.api.mcp.PlatformStream;
import io.casehub.platform.api.mcp.RestPath;
import io.casehub.platform.api.mcp.RestStatus;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@McpDomain(value = "iot/devices", basePath = "/api/devices")
@ApplicationScoped
public class DefaultIoTDeviceApi {

    @Inject DeviceRegistry deviceRegistry;
    @Inject Instance<DeviceProvider> providers;
    @Inject CurrentPrincipal principal;
    @Inject DeviceStateHistoryProvider historyProvider;
    BroadcastProcessor<DeviceResponse> broadcaster;

    @PostConstruct
    void init() {
        broadcaster = BroadcastProcessor.create();
    }


    @PlatformQuery("List devices with optional filtering")
    @RestPath("/")
    public List<DeviceResponse> listDevices(String deviceClass, String providerId,
                                             Boolean available,
                                             @ContextParam("tenancyId") String tenancyId) {
        return deviceRegistry.findAll().stream()
                .filter(d -> d.tenancyId().equals(tenancyId))
                .filter(d -> deviceClass == null || d.deviceClass().name().equals(deviceClass))
                .filter(d -> providerId == null || d.providerId().equals(providerId))
                .filter(d -> available == null || d.available() == available)
                .map(this::toDeviceResponse)
                .toList();
    }

    @PlatformQuery("Get device by ID")
    @RestPath("/{deviceId}")
    public DeviceResponse getDevice(@PathParam String deviceId,
                                     @ContextParam("tenancyId") String tenancyId) {
        var device = deviceRegistry.findById(deviceId)
                .orElseThrow(() -> new NotFoundException("Device not found: " + deviceId));
        if (!device.tenancyId().equals(tenancyId)) {
            throw new NotFoundException("Device not found: " + deviceId);
        }
        return toDeviceResponse(device);
    }

    @PlatformMutation("Dispatch a command to a device")
    @RestPath("/{deviceId}/commands")
    @RestStatus(201)
    public CommandResponse dispatchCommand(@PathParam String deviceId,
                                            CommandRequest command,
                                            @ContextParam("tenancyId") String tenancyId) {
        var device = deviceRegistry.findById(deviceId)
                .orElseThrow(() -> new NotFoundException("Device not found: " + deviceId));
        if (!device.tenancyId().equals(tenancyId)) {
            throw new NotFoundException("Device not found: " + deviceId);
        }
        if (command.action() == null || !DeviceCommand.VALID_ACTIONS.contains(command.action())) {
            throw new BadRequestException("Unknown action '" + command.action()
                    + "'. Valid actions: " + String.join(", ", DeviceCommand.VALID_ACTIONS));
        }
        String correlationId = UUID.randomUUID().toString();
        var deviceCommand = new DeviceCommand(
                deviceId, command.action(), command.parameters(),
                principal.actorId(), correlationId);
        var provider = providers.stream()
                .filter(p -> p.providerId().equals(device.providerId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Provider not found: " + device.providerId()));
        var result = provider.dispatch(deviceCommand);
        return new CommandResponse(deviceId, command.action(), result, correlationId);
    }

    @PlatformQuery("Get device state history")
    @RestPath("/{deviceId}/history")
    public List<StateHistoryView> getDeviceHistory(@PathParam String deviceId,
                                                     Instant from, Instant to, Integer limit,
                                                     @ContextParam("tenancyId") String tenancyId) {
        int effectiveLimit = limit != null ? limit : 100;
        return historyProvider.findHistory(deviceId, tenancyId, from, to, effectiveLimit).stream()
                .map(h -> new StateHistoryView(
                        h.deviceId(), h.deviceClass(),
                        h.stateSnapshot(), h.changedCapabilities(), h.occurredAt()))
                .toList();
    }

    private DeviceResponse toDeviceResponse(DeviceEntity d) {
        return new DeviceResponse(
                d.deviceId(), d.providerId(), d.tenancyId(),
                d.deviceClass().name(), d.label(), d.location(),
                d.available(), d.capabilities(), d.lastUpdated());
    }

    @PlatformStream("Stream device state changes")
    @RestPath("/stream")
    @RolesAllowed("iot-viewer")
    public Multi<DeviceStreamEvent> streamDevices(@ContextParam("tenancyId") String tenancyId) {
        Multi<DeviceStreamEvent> snapshot = Multi.createFrom().item(() -> {
            var devices = deviceRegistry.findAll().stream()
                                        .filter(d -> d.tenancyId().equals(tenancyId))
                                        .map(this::toDeviceResponse)
                                        .toList();
            return new DeviceStreamEvent("snapshot", devices);
        });

        Multi<DeviceStreamEvent> updates = broadcaster
                                                   .filter(d -> d.tenancyId().equals(tenancyId))
                                                   .map(d -> new DeviceStreamEvent("replace", List.of(d)));

        return Multi.createBy().merging().streams(snapshot, updates);
    }

    void onStateChange(@ObservesAsync StateChangeEvent event) {
        var device = event.after();
        broadcaster.onNext(toDeviceResponse(device));
    }


    public record DeviceStreamEvent(String operation, List<DeviceResponse> data) {}
}
