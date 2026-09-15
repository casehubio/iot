package io.casehub.iot.webapp.spi;

import io.casehub.iot.webapp.rest.SuppressionHistoryResponse;
import io.casehub.iot.webapp.rest.SuppressionStatsResponse;
import io.casehub.platform.api.mcp.ContextParam;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PathParam;
import io.casehub.platform.api.mcp.PlatformMutation;
import io.casehub.platform.api.mcp.PlatformQuery;
import io.casehub.platform.api.mcp.RestPath;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@McpDomain(value = "iot/situations/suppressions", basePath = "/api/situations/suppressions")
public interface IoTSuppressionApi {

    @PlatformQuery("List suppression history")
    List<SuppressionHistoryResponse> listSuppressions(String situationId, Instant since,
                                                       Boolean includeOverridden,
                                                       @ContextParam("tenancyId") String tenancyId);

    @PlatformMutation("Override a suppression")
    @RestPath("/{id}/override")
    void overrideSuppression(@PathParam UUID id,
                             @ContextParam("tenancyId") String tenancyId);

    @PlatformQuery("Get suppression statistics for a situation")
    @RestPath("/{situationId}/stats")
    SuppressionStatsResponse getSuppressionStats(@PathParam String situationId,
                                                  @ContextParam("tenancyId") String tenancyId);
}
