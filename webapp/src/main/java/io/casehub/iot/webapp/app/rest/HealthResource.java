package io.casehub.iot.webapp.app.rest;

import io.casehub.iot.api.DeviceRegistry;
import io.casehub.iot.api.provider.DeviceProvider;
import io.casehub.iot.bridge.server.BridgeConnectionRegistry;
import io.casehub.iot.webapp.rest.HealthOverviewResponse;
import io.casehub.platform.api.CurrentPrincipal;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.time.Instant;
import java.util.List;

/**
 * REST resource for system health overview.
 *
 * <p>Endpoint:
 * <ul>
 *   <li>{@code GET /api/health/overview} — composite health metrics
 * </ul>
 *
 * <p>Aggregates provider statuses, bridge connections, active situation count,
 * open case count, and pending WorkItem count.
 */
@Path("/api/health")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class HealthResource {

    @Inject
    Instance<DeviceProvider> providers;

    @Inject
    DeviceRegistry deviceRegistry;

    @Inject
    BridgeConnectionRegistry connectionRegistry;

    @Inject
    EntityManager em;

    @Inject
    CurrentPrincipal principal;

    /**
     * Get composite health overview.
     *
     * @return health metrics including provider statuses, bridge connections,
     * active situations, open cases, and pending WorkItems
     */
    @GET
    @Path("/overview")
    @RolesAllowed("iot-viewer")
    public HealthOverviewResponse overview() {
        // Provider statuses
        var providerStatuses = providers.stream()
                .map(p -> {
                    var status = p.status().await().indefinitely();
                    var deviceCount = (int) deviceRegistry.findAll().stream()
                            .filter(d -> d.providerId().equals(p.providerId()))
                            .filter(d -> filterByTenancy(d.tenancyId()))
                            .count();

                    return new HealthOverviewResponse.ProviderStatus(
                            p.providerId(),
                            status.name(),
                            deviceCount
                    );
                })
                .toList();

        // Bridge connections
        var bridgeConnections = connectionRegistry.connectedTenancies().stream()
                .map(tenancy -> new HealthOverviewResponse.BridgeConnection(
                        tenancy,
                        Instant.now().toString() // connectedSince not available in current SPI
                ))
                .toList();

        // Active situations count (TODO: query RAS persistence)
        int activeSituationCount = 0; // placeholder

        // Open cases count (TODO: query engine)
        int openCaseCount = 0; // placeholder

        // Pending WorkItems count (TODO: query work)
        int pendingWorkItemCount = 0; // placeholder

        return new HealthOverviewResponse(
                providerStatuses,
                bridgeConnections,
                activeSituationCount,
                openCaseCount,
                pendingWorkItemCount
        );
    }

    private boolean filterByTenancy(String deviceTenancyId) {
        return deviceTenancyId == null || deviceTenancyId.equals(principal.tenancyId());
    }
}
