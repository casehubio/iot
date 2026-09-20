package io.casehub.iot.webapp.app.rest;

import io.casehub.iot.api.IoTRoles;
import io.casehub.iot.api.ProviderStatus;
import io.casehub.iot.api.spi.DeviceProvider;
import io.casehub.iot.api.spi.DeviceRegistry;
import io.casehub.iot.bridge.server.BridgeConnectionRegistry;
import io.casehub.iot.webapp.rest.KpiMetric;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.ras.api.SituationStore;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

@Path("/api")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class KpiResource {

    @Inject DeviceRegistry deviceRegistry;
    @Inject Instance<DeviceProvider> providers;
    @Inject CurrentPrincipal principal;
    @Inject BridgeConnectionRegistry connectionRegistry;
    @Inject SituationStore situationStore;

    @GET
    @Path("/devices/kpi")
    @RolesAllowed(IoTRoles.VIEWER)
    public List<KpiMetric> deviceKpi() {
        return deviceKpi(principal.tenancyId());
    }

    List<KpiMetric> deviceKpi(String tenancyId) {
        var devices = deviceRegistry.findAll().stream()
                .filter(d -> d.tenancyId().equals(tenancyId))
                .toList();

        long total = devices.size();
        long online = devices.stream().filter(d -> d.available()).count();
        long providerCount = devices.stream().map(d -> d.providerId()).distinct().count();
        long activeAlerts = situationStore.findActive(tenancyId).size();

        String onlineStatus = total > 0 && online * 2 < total ? "warning" : "normal";
        String alertStatus = activeAlerts > 0 ? "warning" : "normal";

        return List.of(
            new KpiMetric("total-devices", total, "Total Devices", null, "normal"),
            new KpiMetric("online", online, "Online", null, onlineStatus),
            new KpiMetric("providers", providerCount, "Providers", null, "normal"),
            new KpiMetric("active-alerts", activeAlerts, "Active Alerts", null, alertStatus)
        );
    }

    @GET
    @Path("/health/kpi")
    @RolesAllowed(IoTRoles.VIEWER)
    public List<KpiMetric> healthKpi() {
        // Provider and bridge status are global (not per-tenant) — a provider serves all tenants
        long connectedProviders = providers.stream()
                .filter(p -> p.status() == ProviderStatus.CONNECTED)
                .count();
        long bridgeConnections = connectionRegistry.connectedTenancies().size();
        long activeSituations = situationStore.findActive(principal.tenancyId()).size();
        return healthKpi(connectedProviders, bridgeConnections, activeSituations);
    }

    List<KpiMetric> healthKpi(long connectedProviders, long bridgeConnections, long activeSituations) {
        String situationStatus = activeSituations > 0 ? "warning" : "normal";

        return List.of(
            new KpiMetric("connected-providers", connectedProviders, "Connected Providers", null, "normal"),
            new KpiMetric("bridge-connections", bridgeConnections, "Bridge Connections", null, "normal"),
            new KpiMetric("active-situations", activeSituations, "Active Situations", null, situationStatus),
            new KpiMetric("open-cases", 0L, "Open Cases", null, "normal")
        );
    }
}
