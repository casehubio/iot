package io.casehub.iot.webapp.app.rest;

import io.casehub.iot.api.DeviceCommand;
import io.casehub.iot.api.IoTRoles;
import io.casehub.iot.api.spi.DeviceProvider;
import io.casehub.iot.api.spi.DeviceRegistry;
import io.casehub.iot.api.spi.DeviceStateHistoryProvider;
import io.casehub.iot.webapp.rest.CommandRequest;
import io.casehub.iot.webapp.rest.CommandResponse;
import io.casehub.iot.webapp.rest.DeviceResponse;
import io.casehub.platform.api.identity.CurrentPrincipal;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * REST resource for device operations.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET /api/devices} — all devices across all providers
 *   <li>{@code GET /api/devices/{deviceId}} — single device detail
 *   <li>{@code POST /api/devices/{deviceId}/commands} — dispatch command
 *   <li>{@code GET /api/devices/{deviceId}/history} — state change history
 * </ul>
 *
 * <p>All endpoints filter by {@link CurrentPrincipal#tenancyId()} for tenant isolation.
 */
@Path("/api/devices")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DeviceResource {

    @Inject
    DeviceRegistry deviceRegistry;

    @Inject
    Instance<DeviceProvider> providers;

    @Inject
    CurrentPrincipal principal;

    @Inject
    DeviceStateHistoryProvider historyProvider;

    /**
     * List all devices with optional filters.
     *
     * @param deviceClass filter by device class name (e.g., "LIGHT", "THERMOSTAT")
     * @param providerId  filter by provider ID
     * @param available   filter by availability (true/false)
     * @return filtered list of devices
     */
    @GET
    @RolesAllowed(IoTRoles.VIEWER)
    public List<DeviceResponse> list(
            @QueryParam("deviceClass") String deviceClass,
            @QueryParam("providerId") String providerId,
            @QueryParam("available") Boolean available
                                    ) {
        return deviceRegistry.findAll().stream()
                             .filter(d -> filterByTenancy(d.tenancyId()))
                             .filter(d -> deviceClass == null || d.deviceClass().name().equals(deviceClass))
                             .filter(d -> providerId == null || d.providerId().equals(providerId))
                             .filter(d -> available == null || d.available() == available)
                             .map(d -> new DeviceResponse(
                                     d.deviceId(),
                                     d.providerId(),
                                     d.tenancyId(),
                                     d.deviceClass().name(),
                                     d.label(),
                                     d.location(),
                                     d.available(),
                                     d.capabilities(),
                                     d.lastUpdated()
                             ))
                             .toList();
    }

    /**
     * Get a single device by ID.
     *
     * @param deviceId device ID
     * @return device detail
     * @throws NotFoundException if device not found or not visible to current tenant
     */
    @GET
    @Path("/{deviceId}")
    @RolesAllowed(IoTRoles.VIEWER)
    public DeviceResponse get(@PathParam("deviceId") String deviceId) {
        var device = deviceRegistry.findById(deviceId)
                                   .orElseThrow(() -> new NotFoundException("Device not found: " + deviceId));

        if (!filterByTenancy(device.tenancyId())) {
            throw new NotFoundException("Device not found: " + deviceId);
        }

        return new DeviceResponse(
                device.deviceId(),
                device.providerId(),
                device.tenancyId(),
                device.deviceClass().name(),
                device.label(),
                device.location(),
                device.available(),
                device.capabilities(),
                device.lastUpdated()
        );
    }

    /**
     * Dispatch a command to a device.
     *
     * @param deviceId device ID
     * @param request  command action and parameters
     * @return command result
     */
    @POST
    @Path("/{deviceId}/commands")
    @RolesAllowed(IoTRoles.OPERATOR)
    @Transactional
    public CommandResponse dispatch(
            @PathParam("deviceId") String deviceId,
            CommandRequest request
                                   ) {
        var device = deviceRegistry.findById(deviceId)
                                   .orElseThrow(() -> new NotFoundException("Device not found: " + deviceId));

        if (!filterByTenancy(device.tenancyId())) {
            throw new NotFoundException("Device not found: " + deviceId);
        }

        String correlationId = UUID.randomUUID().toString();

        // Build command
        var command = new DeviceCommand(
                deviceId,
                request.action(),
                request.parameters(),
                principal.actorId(), // dispatchedBy
                correlationId
        );

        // Find provider and dispatch
        var provider = providers.stream()
                                .filter(p -> p.providerId().equals(device.providerId()))
                                .findFirst()
                                .orElseThrow(() -> new IllegalStateException("Provider not found: " + device.providerId()));

        var result = provider.dispatch(command);

        return new CommandResponse(
                deviceId,
                request.action(),
                result,
                correlationId
        );
    }

    @GET
    @Path("/{deviceId}/history")
    @RolesAllowed(IoTRoles.VIEWER)
    public List<StateHistoryResponse> history(
            @PathParam("deviceId") String deviceId,
            @QueryParam("from") Instant from,
            @QueryParam("to") Instant to,
            @QueryParam("limit") @DefaultValue("100") int limit
                                             ) {
        return historyProvider.findHistory(deviceId, principal.tenancyId(), from, to, limit).stream()
                              .map(h -> new StateHistoryResponse(
                                      h.deviceId(),
                                      h.deviceClass(),
                                      h.stateSnapshot(),
                                      h.changedCapabilities(),
                                      h.occurredAt()
                              ))
                              .toList();
    }

    private boolean filterByTenancy(String deviceTenancyId) {return deviceTenancyId.equals(principal.tenancyId());}

    /**
     * State history response record.
     */
    public record StateHistoryResponse(
            String deviceId,
            String deviceClass,
            Object stateSnapshot,
            List<String> changedCapabilities,
            Instant occurredAt
    ) {
    }
}
