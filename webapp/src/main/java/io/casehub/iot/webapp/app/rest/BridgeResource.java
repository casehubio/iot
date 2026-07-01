package io.casehub.iot.webapp.app.rest;

import io.casehub.iot.api.bridge.BridgeAuditQuery;
import io.casehub.iot.api.bridge.BridgeAuditStore;
import io.casehub.iot.bridge.server.BridgeConnectionRegistry;
import io.casehub.platform.api.CurrentPrincipal;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.time.Instant;
import java.util.List;

/**
 * REST resource for bridge connectivity and audit operations.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET /api/bridge/connections} — connected tenancies
 *   <li>{@code GET /api/bridge/audit} — audit trail with filters
 * </ul>
 */
@Path("/api/bridge")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class BridgeResource {

    @Inject
    BridgeConnectionRegistry connectionRegistry;

    @Inject
    BridgeAuditStore auditStore;

    @Inject
    CurrentPrincipal principal;

    /**
     * Get connected bridge tenancies.
     *
     * @return list of connected tenancies with connection info
     */
    @GET
    @Path("/connections")
    @RolesAllowed("iot-viewer")
    public BridgeConnectionsResponse connections() {
        var connectedTenancies = connectionRegistry.connectedTenancies();
        var hasAnyConnection = connectionRegistry.hasAnyConnection();

        return new BridgeConnectionsResponse(
                connectedTenancies.stream()
                        .map(tenancy -> new TenancyConnection(
                                tenancy,
                                null // connectedSince not available in current SPI
                        ))
                        .toList(),
                hasAnyConnection
        );
    }

    /**
     * Query audit trail with optional filters.
     *
     * <p>Always scoped to current principal's tenancy — cross-tenant access blocked.
     *
     * @param eventType     filter by event type (e.g., "state_change", "command_dispatch")
     * @param deviceId      filter by device ID
     * @param correlationId filter by correlation ID
     * @param from          start time
     * @param to            end time
     * @param offset        pagination offset (default 0)
     * @param limit         max results (default 100, max 500)
     * @return audit records matching filters
     */
    @GET
    @Path("/audit")
    @RolesAllowed("iot-viewer")
    public AuditTrailResponse audit(
            @QueryParam("eventType") String eventType,
            @QueryParam("deviceId") String deviceId,
            @QueryParam("correlationId") String correlationId,
            @QueryParam("from") Instant from,
            @QueryParam("to") Instant to,
            @QueryParam("offset") @DefaultValue("0") int offset,
            @QueryParam("limit") @DefaultValue("100") int limit
    ) {
        // Always use current principal's tenancy — no cross-tenant access
        String effectiveTenancyId = principal.tenancyId();

        // Build query with filters
        var queryBuilder = BridgeAuditQuery.builder()
                .tenancyId(effectiveTenancyId);

        if (eventType != null) {
            queryBuilder.eventType(eventType);
        }
        if (deviceId != null) {
            queryBuilder.deviceId(deviceId);
        }
        if (correlationId != null) {
            queryBuilder.correlationId(correlationId);
        }
        if (from != null) {
            queryBuilder.from(from);
        }
        if (to != null) {
            queryBuilder.to(to);
        }

        queryBuilder.offset(offset).limit(Math.min(limit, 500));

        var result = auditStore.query(queryBuilder.build()).await().indefinitely();

        return new AuditTrailResponse(
                result.records().stream()
                        .map(r -> new AuditRecord(
                                r.id(),
                                r.tenancyId(),
                                r.eventType(),
                                r.deviceId(),
                                r.correlationId(),
                                r.message(),
                                r.occurredAt()
                        ))
                        .toList(),
                result.total(),
                offset,
                limit
        );
    }

    public record BridgeConnectionsResponse(
            List<TenancyConnection> connections,
            boolean hasAnyConnection
    ) {
    }

    public record TenancyConnection(
            String tenancyId,
            Instant connectedSince
    ) {
    }

    public record AuditTrailResponse(
            List<AuditRecord> records,
            long total,
            int offset,
            int limit
    ) {
    }

    public record AuditRecord(
            String id,
            String tenancyId,
            String eventType,
            String deviceId,
            String correlationId,
            Object message,
            Instant occurredAt
    ) {
    }
}
