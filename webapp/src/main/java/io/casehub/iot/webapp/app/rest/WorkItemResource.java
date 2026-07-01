package io.casehub.iot.webapp.app.rest;

import io.casehub.platform.api.CurrentPrincipal;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST resource for WorkItem operations.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET /api/workitems} — pending human tasks
 *   <li>{@code POST /api/workitems/{workItemId}/claim} — claim a task
 *   <li>{@code POST /api/workitems/{workItemId}/complete} — complete with outcome
 * </ul>
 *
 * <p>All queries filter by {@link CurrentPrincipal#tenancyId()}.
 *
 * <p>TODO: Integrate with casehub-work's {@code WorkItemService} or equivalent API.
 * Current implementation is a placeholder until work APIs are available.
 */
@Path("/api/workitems")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class WorkItemResource {

    @Inject
    EntityManager em;

    @Inject
    CurrentPrincipal principal;

    /**
     * List pending WorkItems with optional filters.
     *
     * @param status filter by status (e.g., "OPEN", "CLAIMED", "EXPIRED")
     * @param caseId filter by case ID
     * @return list of WorkItem summaries
     */
    @GET
    @RolesAllowed("iot-viewer")
    public List<WorkItemSummaryResponse> list(
            @QueryParam("status") String status,
            @QueryParam("caseId") UUID caseId
    ) {
        // TODO: Query casehub-work's WorkItemService
        // For now, return empty list until work integration is complete
        return List.of();
    }

    /**
     * Claim a WorkItem.
     *
     * @param workItemId WorkItem ID
     * @return claimed WorkItem
     */
    @POST
    @Path("/{workItemId}/claim")
    @RolesAllowed("iot-operator")
    @Transactional
    public WorkItemDetailResponse claim(@PathParam("workItemId") UUID workItemId) {
        // TODO: Call WorkItemService.claim(workItemId, principal.userId())
        // Verify tenancy matches principal.tenancyId()
        throw new NotFoundException("WorkItem not found: " + workItemId);
    }

    /**
     * Complete a WorkItem with outcome.
     *
     * @param workItemId WorkItem ID
     * @param request    completion outcome
     * @return completed WorkItem
     */
    @POST
    @Path("/{workItemId}/complete")
    @RolesAllowed("iot-operator")
    @Transactional
    public WorkItemDetailResponse complete(
            @PathParam("workItemId") UUID workItemId,
            CompleteRequest request
    ) {
        // TODO: Call WorkItemService.complete(workItemId, request.outcome(), request.data())
        // Verify tenancy and ownership
        throw new NotFoundException("WorkItem not found: " + workItemId);
    }

    public record WorkItemSummaryResponse(
            UUID workItemId,
            UUID caseId,
            String description,
            String status,
            String priority,
            Instant slaDeadline,
            Instant createdAt
    ) {
    }

    public record WorkItemDetailResponse(
            UUID workItemId,
            UUID caseId,
            String description,
            String status,
            String priority,
            Map<String, Object> context,
            List<String> availableOutcomes,
            Instant slaDeadline,
            Instant createdAt,
            Instant claimedAt,
            String claimedBy
    ) {
    }

    public record CompleteRequest(
            String outcome,
            Map<String, Object> data
    ) {
    }
}
