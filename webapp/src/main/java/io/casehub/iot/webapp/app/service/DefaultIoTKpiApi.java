package io.casehub.iot.webapp.app.service;

import io.casehub.iot.api.IoTRoles;
import io.casehub.iot.api.ProviderStatus;
import io.casehub.iot.api.spi.DeviceProvider;
import io.casehub.iot.api.spi.DeviceRegistry;
import io.casehub.iot.bridge.server.BridgeConnectionRegistry;
import io.casehub.iot.webapp.rest.KpiMetric;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PlatformQuery;
import io.casehub.ras.api.SituationStore;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.List;

@McpDomain(value = "iot/kpis", basePath = "/api")
@ApplicationScoped
public class DefaultIoTKpiApi {

    @Inject DeviceRegistry deviceRegistry;
    @Inject Instance<DeviceProvider> providers;
    @Inject CurrentPrincipal principal;
    @Inject BridgeConnectionRegistry connectionRegistry;
    @Inject SituationStore situationStore;

    @PlatformQuery("Get device KPI metrics")
    @RolesAllowed(IoTRoles.VIEWER)
    public List<KpiMetric> deviceKpi() {
        var tenancyId = principal.tenancyId();
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

    @PlatformQuery("Get system health KPI metrics")
    @RolesAllowed(IoTRoles.VIEWER)
    public List<KpiMetric> healthKpi() {
        long connectedProviders = providers.stream()
                .filter(p -> p.status() == ProviderStatus.CONNECTED)
                .count();
        long bridgeConnections = connectionRegistry.connectedTenancies().size();
        long activeSituations = situationStore.findActive(principal.tenancyId()).size();

        String situationStatus = activeSituations > 0 ? "warning" : "normal";

        return List.of(
                new KpiMetric("connected-providers", connectedProviders, "Connected Providers", null, "normal"),
                new KpiMetric("bridge-connections", bridgeConnections, "Bridge Connections", null, "normal"),
                new KpiMetric("active-situations", activeSituations, "Active Situations", null, situationStatus),
                new KpiMetric("open-cases", 0L, "Open Cases", null, "normal")
        );
    }
}
