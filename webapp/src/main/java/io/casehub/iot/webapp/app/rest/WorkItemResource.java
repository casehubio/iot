package io.casehub.iot.webapp.app.rest;

import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.iot.webapp.cbr.WorkItemContext;
import io.casehub.iot.webapp.cbr.WorkItemFeatureExtractor;
import io.casehub.iot.webapp.cbr.WorkItemPrediction;
import io.casehub.iot.webapp.cbr.WorkItemPredictionService;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.work.api.WorkItem;
import io.casehub.work.runtime.service.WorkItemService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
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
    CurrentPrincipal principal;
    @Inject
    WorkItemService  workItemService;

    @Inject
    WorkItemPredictionService predictionService;

    @Inject
    CaseInstanceCache caseInstanceCache;


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

    @GET
    @Path("/{workItemId}/prediction")
    @RolesAllowed("iot-viewer")
    public WorkItemPredictionResponse prediction(@PathParam("workItemId") UUID workItemId) {
        var workItemOpt = workItemService.findById(workItemId);
        if (workItemOpt.isEmpty()) {
            throw new NotFoundException("WorkItem not found: " + workItemId);
        }
        var workItem = workItemOpt.get();
        if (!principal.tenancyId().equals(workItem.tenancyId())) {
            throw new NotFoundException("WorkItem not found: " + workItemId);
        }

        try {
            var ctx        = buildPredictionContext(workItem);
            var features   = WorkItemFeatureExtractor.extractForRetrieve(ctx);
            var prediction = predictionService.predict(features, principal.tenancyId());
            return WorkItemPredictionResponse.from(workItemId, prediction);
        } catch (Exception e) {
            org.jboss.logging.Logger.getLogger(WorkItemResource.class)
                                    .warnv(e, "CBR prediction failed for work item {0}", workItemId);
            return WorkItemPredictionResponse.empty(workItemId);
        }
    }

    @SuppressWarnings("unchecked")
    private WorkItemContext buildPredictionContext(WorkItem workItem) {
        Map<String, Object> payload = Map.of();
        if (workItem.payload() != null && !workItem.payload().isBlank()) {
            try {
                payload = new com.fasterxml.jackson.databind.ObjectMapper()
                                  .readValue(workItem.payload(), Map.class);
            } catch (Exception ignored) {}
        }

        String deviceClass = (String) payload.get("deviceClass");
        String roomType    = (String) payload.get("roomType");
        String caseType    = (String) payload.get("caseType");
        String workerName  = (String) payload.get("workerName");
        String eventTs     = (String) payload.get("eventTimestamp");

        if (caseType == null) {
            var caseOpt = caseInstanceCache.getAll().stream()
                                           .filter(ci -> principal.tenancyId().equals(ci.tenancyId))
                                           .filter(ci -> workItem.id().toString().equals(ci.getWaitingForWorkId()))
                                           .findFirst();
            if (caseOpt.isPresent()) {
                var ci = caseOpt.get();
                caseType = ci.getCaseMetaModel().getName();
                var working = ci.getCaseContext().layer("working");
                if (deviceClass == null) {
                    Object dc = working.get("deviceClass");
                    if (dc instanceof String s) {deviceClass = s;}
                }
                if (roomType == null) {
                    Object rt = working.get("roomType");
                    if (rt instanceof String s) {roomType = s;}
                }
            }
        }

        return new WorkItemContext(
                workItem.title(), workItem.description(),
                workItem.types() != null
                ? java.util.List.copyOf(workItem.types())
                : java.util.List.of(),
                workItem.priority() != null ? workItem.priority().name() : "MEDIUM",
                workItem.candidateGroups(),
                workerName != null ? workerName : "unknown",
                caseType != null ? caseType : "unknown",
                deviceClass, roomType,
                eventTs != null ? java.time.Instant.parse(eventTs) : null,
                null, null, null, null);
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

    public record WorkItemPredictionResponse(
            UUID workItemId,
            Map<String, Double> outcomeDistribution,
            ResolutionTimeResponse resolutionTime,
            List<AssigneeSuggestionResponse> suggestedAssignees,
            double confidence,
            int sampleSize
    ) {
        static WorkItemPredictionResponse from(UUID workItemId, WorkItemPrediction p) {
            var rt = (p.resolutionTimeP50() != null)
                     ? new ResolutionTimeResponse(p.resolutionTimeP50().toString(), p.resolutionTimeP90().toString())
                     : null;
            var assignees = p.suggestedAssignees().stream()
                             .map(a -> new AssigneeSuggestionResponse(
                                     a.assigneeId(), a.successRate(),
                                     a.avgResolutionTime() != null ? a.avgResolutionTime().toString() : null,
                                     a.taskCount()))
                             .toList();
            return new WorkItemPredictionResponse(
                    workItemId, p.outcomeDistribution(), rt, assignees,
                    p.confidence(), p.sampleSize());
        }

        static WorkItemPredictionResponse empty(UUID workItemId) {
            return new WorkItemPredictionResponse(
                    workItemId, Map.of(), null, List.of(), 0.0, 0);
        }

        public record ResolutionTimeResponse(String p50, String p90) {}

        public record AssigneeSuggestionResponse(
                String assigneeId, double successRate,
                String avgResolutionTime, int taskCount) {}
    }

}
