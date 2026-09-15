package io.casehub.iot.webapp.spi;

import io.casehub.iot.webapp.rest.HealthOverviewResponse;
import io.casehub.iot.webapp.view.AuditTrailView;
import io.casehub.iot.webapp.view.BridgeConnectionsView;
import io.casehub.iot.webapp.view.ProviderStatusView;
import io.casehub.iot.webapp.view.RefreshResultView;
import io.casehub.platform.api.mcp.ContextParam;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PathParam;
import io.casehub.platform.api.mcp.PlatformMutation;
import io.casehub.platform.api.mcp.PlatformQuery;
import io.casehub.platform.api.mcp.RestPath;

import java.time.Instant;
import java.util.List;

@McpDomain(value = "iot/ops", basePath = "/api")
public interface IoTOperationsApi {

    @PlatformQuery("List IoT providers with status")
    @RestPath("/providers")
    List<ProviderStatusView> listProviders(@ContextParam("tenancyId") String tenancyId);

    @PlatformQuery("Get provider by ID")
    @RestPath("/providers/{providerId}")
    ProviderStatusView getProvider(@PathParam String providerId,
                                   @ContextParam("tenancyId") String tenancyId);

    @PlatformMutation("Refresh all providers")
    @RestPath("/providers/refresh")
    RefreshResultView refreshAllProviders(@ContextParam("tenancyId") String tenancyId);

    @PlatformMutation("Refresh a specific provider")
    @RestPath("/providers/{providerId}/refresh")
    RefreshResultView refreshProvider(@PathParam String providerId,
                                      @ContextParam("tenancyId") String tenancyId);

    @PlatformQuery("List bridge connections")
    @RestPath("/bridge/connections")
    BridgeConnectionsView getBridgeConnections(@ContextParam("tenancyId") String tenancyId);

    @PlatformQuery("Query bridge audit trail")
    @RestPath("/bridge/audit")
    AuditTrailView getBridgeAudit(String eventType, String deviceId, String correlationId,
                                   Instant from, Instant to, Integer offset, Integer limit,
                                   @ContextParam("tenancyId") String tenancyId);

    @PlatformQuery("Get system health overview")
    @RestPath("/health/overview")
    HealthOverviewResponse getHealthOverview(@ContextParam("tenancyId") String tenancyId);
}
