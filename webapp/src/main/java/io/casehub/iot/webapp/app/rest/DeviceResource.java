package io.casehub.iot.webapp.app.rest;

import io.casehub.iot.api.DeviceRegistry;
import io.casehub.iot.api.command.DeviceCommand;
import io.casehub.iot.api.provider.DeviceProvider;
import io.casehub.iot.webapp.app.persistence.IoTDeviceStateHistoryEntity;
import io.casehub.iot.webapp.rest.CommandRequest;
import io.casehub.iot.webapp.rest.CommandResponse;
import io.casehub.iot.webapp.rest.DeviceResponse;
import io.casehub.platform.api.CurrentPrincipal;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
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
    EntityManager em;

    /**
     * List all devices with optional filters.
     *
     * @param deviceClass filter by device class name (e.g., "LIGHT", "THERMOSTAT")
     * @param providerId  filter by provider ID
     * @param available   filter by availability (true/false)
     * @return filtered list of devices
     */
    @GET
    @RolesAllowed("iot-viewer")
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
                        d.name(),
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
    @RolesAllowed("iot-viewer")
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
                device.name(),
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
    @RolesAllowed("iot-operator")
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
        var command = DeviceCommand.of(
                deviceId,
                request.action(),
                request.parameters(),
                principal.tenancyId(), // dispatchedBy
                correlationId
        );

        // Find provider and dispatch
        var provider = providers.stream()
                .filter(p -> p.providerId().equals(device.providerId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Provider not found: " + device.providerId()));

        var result = provider.dispatch(command).await().indefinitely();

        return new CommandResponse(
                deviceId,
                request.action(),
                result,
                correlationId
        );
    }

    /**
     * Get state change history for a device.
     *
     * @param deviceId device ID
     * @param from     start time (optional)
     * @param to       end time (optional)
     * @param limit    max results (default 100)
     * @return list of state history entries
     */
    @GET
    @Path("/{deviceId}/history")
    @RolesAllowed("iot-viewer")
    public List<StateHistoryResponse> history(
            @PathParam("deviceId") String deviceId,
            @QueryParam("from") Instant from,
            @QueryParam("to") Instant to,
            @QueryParam("limit") @DefaultValue("100") int limit
    ) {
        var device = deviceRegistry.findById(deviceId)
                .orElseThrow(() -> new NotFoundException("Device not found: " + deviceId));

        if (!filterByTenancy(device.tenancyId())) {
            throw new NotFoundException("Device not found: " + deviceId);
        }

        var query = em.createQuery(
                """
                SELECT h FROM IoTDeviceStateHistoryEntity h
                WHERE h.deviceId = :deviceId
                  AND h.tenancyId = :tenancyId
                  AND (:from IS NULL OR h.occurredAt >= :from)
                  AND (:to IS NULL OR h.occurredAt <= :to)
                ORDER BY h.occurredAt DESC
                """,
                IoTDeviceStateHistoryEntity.class
        );

        query.setParameter("deviceId", deviceId);
        query.setParameter("tenancyId", principal.tenancyId());
        query.setParameter("from", from);
        query.setParameter("to", to);
        query.setMaxResults(limit);

        return query.getResultList().stream()
                .map(h -> new StateHistoryResponse(
                        h.getDeviceId(),
                        h.getDeviceClass(),
                        h.getStateSnapshot(),
                        h.getChangedCapabilities(),
                        h.getOccurredAt()
                ))
                .toList();
    }

    private boolean filterByTenancy(String deviceTenancyId) {
        // Null tenancy ID means system-wide device (bridge agents, cross-tenant providers)
        return deviceTenancyId == null || deviceTenancyId.equals(principal.tenancyId());
    }

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
