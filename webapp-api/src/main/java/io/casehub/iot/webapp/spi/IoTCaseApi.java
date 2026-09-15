package io.casehub.iot.webapp.spi;

import io.casehub.iot.webapp.NotImplementedException;
import io.casehub.iot.webapp.resolution.QueueEntryDetail;
import io.casehub.iot.webapp.resolution.QueueEntrySummary;
import io.casehub.iot.webapp.view.CaseDetailView;
import io.casehub.iot.webapp.view.CaseSummaryView;
import io.casehub.iot.webapp.view.SuggestionView;
import io.casehub.platform.api.mcp.ContextParam;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PathParam;
import io.casehub.platform.api.mcp.PlatformMutation;
import io.casehub.platform.api.mcp.PlatformQuery;
import io.casehub.platform.api.mcp.RestPath;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@McpDomain(value = "iot/cases", basePath = "/api/cases")
public interface IoTCaseApi {

    @PlatformQuery("List cases with optional filtering")
    default List<CaseSummaryView> listCases(String status, String situationId,
                                             Instant from, Instant to,
                                             @ContextParam("tenancyId") String tenancyId) {
        throw new NotImplementedException("listCases");
    }

    @PlatformQuery("Get case by ID")
    @RestPath("/{caseId}")
    default CaseDetailView getCase(@PathParam UUID caseId,
                                    @ContextParam("tenancyId") String tenancyId) {
        throw new NotImplementedException("getCase");
    }

    @PlatformQuery("Get case resolution suggestions")
    @RestPath("/{caseId}/suggestions")
    SuggestionView getCaseSuggestions(@PathParam UUID caseId,
                                      @ContextParam("tenancyId") String tenancyId);

    @PlatformMutation("Accept a resolution suggestion for a case")
    @RestPath("/{caseId}/suggestions/{pastCaseId}/accept")
    void acceptSuggestion(@PathParam UUID caseId, @PathParam String pastCaseId,
                          @ContextParam("tenancyId") String tenancyId);

    @PlatformQuery("List resolution queue entries")
    @RestPath("/resolution/queue")
    List<QueueEntrySummary> listResolutionQueue(String view, String status,
                                                 @ContextParam("tenancyId") String tenancyId);

    @PlatformQuery("Get resolution queue entry detail")
    @RestPath("/resolution/queue/{entryId}")
    QueueEntryDetail getResolutionQueueEntry(@PathParam UUID entryId,
                                              @ContextParam("tenancyId") String tenancyId);
}
