package io.casehub.iot.webapp.app.rest;

import io.casehub.platform.api.identity.CurrentPrincipal;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * REST resource for case instance queries.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET /api/cases} — open cases triggered by situations
 *   <li>{@code GET /api/cases/{caseId}} — case detail with event log and worker results
 * </ul>
 *
 * <p>All queries filter by {@link CurrentPrincipal#tenancyId()}.
 *
 * <p>TODO: Integrate with casehub-engine's {@code CaseInstanceRepository} or equivalent
 * API for querying case instances. Current implementation is a placeholder until
 * engine APIs are available.
 */
@Path("/api/cases")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CaseResource {

    @Inject
    CurrentPrincipal principal;

    /**
     * List open cases with optional filters.
     *
     * @param status      filter by case status (e.g., "OPEN", "COMPLETED")
     * @param situationId filter by source situation ID
     * @param from        start time
     * @param to          end time
     * @return list of case summaries
     */
    @GET
    @RolesAllowed("iot-viewer")
    public List<CaseSummaryResponse> list(
            @QueryParam("status") String status,
            @QueryParam("situationId") String situationId,
            @QueryParam("from") Instant from,
            @QueryParam("to") Instant to
    ) {
        // TODO: Query engine's CaseInstanceRepository
        // For now, return empty list until engine integration is complete
        return List.of();
    }

    /**
     * Get case detail with event log and worker results.
     *
     * @param caseId case instance ID
     * @return case detail
     */
    @GET
    @Path("/{caseId}")
    @RolesAllowed("iot-viewer")
    public CaseDetailResponse get(@PathParam("caseId") UUID caseId) {
        // TODO: Query engine for case instance + event log + worker outcomes
        // Check tenancy via principal.tenancyId()
        throw new NotFoundException("Case not found: " + caseId);
    }

    public record CaseSummaryResponse(
            UUID caseId,
            String caseType,
            String status,
            String situationId,
            int pendingActionsCount,
            Instant createdAt
    ) {
    }

    public record CaseDetailResponse(
            UUID caseId,
            String caseType,
            String status,
            String situationId,
            List<CaseEvent> eventLog,
            List<WorkerResult> workerResults,
            List<PlannedAction> plannedActions,
            Instant createdAt,
            Instant completedAt
    ) {
    }

    public record CaseEvent(
            Instant timestamp,
            String eventType,
            Object payload
    ) {
    }

    public record WorkerResult(
            String workerId,
            String outcome,
            Object data,
            Instant executedAt
    ) {
    }

    public record PlannedAction(
            String actionId,
            String description,
            String status,
            Instant scheduledAt
    ) {
    }
}
