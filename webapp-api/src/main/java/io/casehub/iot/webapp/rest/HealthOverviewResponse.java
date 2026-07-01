package io.casehub.iot.webapp.rest;

import java.util.List;

/**
 * REST response record for composite health overview.
 *
 * <p>Used by {@code GET /api/health/overview}. Aggregates provider statuses,
 * bridge connections, active situation count, open case count, and pending
 * WorkItem count in one response.
 */
public record HealthOverviewResponse(
        List<ProviderStatus> providers,
        List<BridgeConnection> bridgeConnections,
        int activeSituationCount,
        int openCaseCount,
        int pendingWorkItemCount
) {

    public record ProviderStatus(
            String providerId,
            String status,
            int deviceCount
    ) {
    }

    public record BridgeConnection(
            String tenancyId,
            String connectedSince
    ) {
    }
}
