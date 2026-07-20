package io.casehub.iot.webapp.app.rest;

import io.casehub.iot.webapp.app.persistence.IoTSituationDefinitionEntity;
import io.casehub.iot.webapp.rest.SituationDefinitionRequest;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.ras.api.CaseTriggerConfig;
import io.casehub.ras.api.ChainMode;
import io.casehub.ras.api.SituationDefinition;
import io.casehub.ras.api.TriggerAction;
import io.casehub.ras.api.TriggerMode;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.time.Instant;
import java.util.List;

/**
 * REST resource for situation definition management.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET /api/situations/definitions} — all situation definitions (classpath + runtime)
 *   <li>{@code POST /api/situations/definitions} — create runtime definition
 *   <li>{@code PUT /api/situations/definitions/{situationId}} — update runtime definition
 *   <li>{@code DELETE /api/situations/definitions/{situationId}} — delete runtime definition
 *   <li>{@code GET /api/situations/active} — active situation contexts
 * </ul>
 *
 * <p>Runtime definitions are stored in {@code iot_situation_definition} table,
 * scoped by {@link CurrentPrincipal#tenancyId()}. Database definitions override
 * classpath defaults with the same {@code situationId}.
 */
@Path("/api/situations")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SituationResource {

    @Inject
    EntityManager em;

    @Inject
    CurrentPrincipal                                     principal;
    @Inject
    io.casehub.engine.common.spi.cache.CaseInstanceCache caseInstanceCache;
    @Inject
    io.casehub.engine.common.spi.CaseDefinitionRegistry  caseDefinitionRegistry;
    @Inject
    io.casehub.iot.webapp.cbr.IoTCbrRetrievalService     retrievalService;
    @Inject
    io.casehub.iot.webapp.cbr.DismissalRecorder          dismissalRecorder;

    @Inject
    io.casehub.ras.api.SituationStore situationStore;

    @Inject
    jakarta.enterprise.event.Event<io.casehub.ras.api.SituationChangeEvent> changeEvent;


    /**
     * Map REST request to domain SituationDefinition.
     *
     * @param request REST request with nested chain/trigger mode structures
     * @return domain SituationDefinition with sealed types
     */
    private static SituationDefinition mapRequestToDomain(SituationDefinitionRequest request) {
        var chainMode   = mapChainMode(request.chainMode());
        var triggerMode = mapTriggerMode(request.triggerMode());
        var triggerConfig = new CaseTriggerConfig(
                request.triggerConfig().caseNamespace(),
                request.triggerConfig().caseName(),
                request.triggerConfig().caseVersion(),
                request.triggerConfig().baseCaseData()
        );

        return new SituationDefinition(
                request.situationId(),
                request.eventTypes(),
                request.correlationWindow(),
                request.eventBufferDelay(),
                chainMode,
                new TriggerAction.CreateCase(triggerConfig),
                triggerMode
        );
    }

    private static ChainMode mapChainMode(SituationDefinitionRequest.ChainModeRequest request) {
        return switch (request.type()) {
            case "and" -> new ChainMode.And(request.ganglia());
            case "or" -> new ChainMode.Or(request.ganglia());
            case "threshold" -> new ChainMode.Threshold(request.ganglia(), request.minConfidence());
            case "count" -> new ChainMode.Count(request.ganglionId(), request.requiredCount());
            default -> throw new BadRequestException("Unknown chain mode type: " + request.type());
        };
    }

    private static TriggerMode mapTriggerMode(SituationDefinitionRequest.TriggerModeRequest request) {
        if (request == null) {
            return new TriggerMode.FireOnce();
        }
        return switch (request.type()) {
            case "fire_once" -> new TriggerMode.FireOnce();
            case "repeating" -> new TriggerMode.Repeating(request.cooldown());
            default -> throw new BadRequestException("Unknown trigger mode type: " + request.type());
        };
    }

    /**
     * List all situation definitions for current tenant.
     *
     * <p>Merges classpath defaults with runtime overrides. Runtime definitions
     * with matching {@code situationId} take precedence.
     *
     * @return list of situation definitions
     */
    @GET
    @Path("/definitions")
    @RolesAllowed("iot-viewer")
    public List<SituationDefinitionResponse> listDefinitions() {
        var runtimeDefs = em.createQuery(
                                    "SELECT s FROM IoTSituationDefinitionEntity s WHERE s.tenancyId = :tenancyId",
                                    IoTSituationDefinitionEntity.class
                                        )
                            .setParameter("tenancyId", principal.tenancyId())
                            .getResultList();

        return runtimeDefs.stream()
                          .map(def -> new SituationDefinitionResponse(
                                  def.getSituationId(),
                                  def.getTenancyId(),
                                  def.getDefinition(), // full SituationDefinition JSONB
                                  def.getCreatedAt(),
                                  def.getUpdatedAt(),
                                  "runtime"
                          ))
                          .toList();

        // TODO: merge with classpath definitions from SituationDefinitionProvider
        // For now, returning only runtime definitions until RAS integration is complete
    }

    /**
     * Create a new runtime situation definition.
     *
     * @param request situation definition request
     * @return created definition
     */
    @POST
    @Path("/definitions")
    @RolesAllowed("iot-admin")
    @Transactional
    public SituationDefinitionResponse createDefinition(SituationDefinitionRequest request) {
        // Check for existing definition with same situationId for this tenant
        var existing = em.createQuery(
                                 "SELECT COUNT(s) FROM IoTSituationDefinitionEntity s WHERE s.situationId = :situationId AND s.tenancyId = :tenancyId",
                                 Long.class
                                     )
                         .setParameter("situationId", request.situationId())
                         .setParameter("tenancyId", principal.tenancyId())
                         .getSingleResult();

        if (existing > 0) {
            throw new BadRequestException("Situation definition already exists: " + request.situationId());
        }

        var now        = Instant.now();
        var definition = mapRequestToDomain(request);

        var entity = new IoTSituationDefinitionEntity(
                request.situationId(),
                principal.tenancyId(),
                definition,
                now,
                now
        );

        em.persist(entity);

        return new SituationDefinitionResponse(
                entity.getSituationId(),
                entity.getTenancyId(),
                entity.getDefinition(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                "runtime"
        );
    }

    /**
     * Update an existing runtime situation definition.
     *
     * <p>Immutable entity pattern: delete old, create new with updated fields.
     *
     * @param situationId situation ID
     * @param request     updated definition
     * @return updated definition
     */
    @PUT
    @Path("/definitions/{situationId}")
    @RolesAllowed("iot-admin")
    @Transactional
    public SituationDefinitionResponse updateDefinition(
            @PathParam("situationId") String situationId,
            SituationDefinitionRequest request
                                                       ) {
        var existingEntity = em.createQuery(
                                       "SELECT s FROM IoTSituationDefinitionEntity s WHERE s.situationId = :situationId AND s.tenancyId = :tenancyId",
                                       IoTSituationDefinitionEntity.class
                                           )
                               .setParameter("situationId", situationId)
                               .setParameter("tenancyId", principal.tenancyId())
                               .getResultStream()
                               .findFirst()
                               .orElseThrow(() -> new NotFoundException("Situation definition not found: " + situationId));

        // Immutable entity — delete old, create new
        em.remove(existingEntity);
        em.flush();

        var now        = Instant.now();
        var definition = mapRequestToDomain(request);

        var newEntity = new IoTSituationDefinitionEntity(
                request.situationId(),
                principal.tenancyId(),
                definition,
                existingEntity.getCreatedAt(), // preserve original creation time
                now
        );

        em.persist(newEntity);

        return new SituationDefinitionResponse(
                newEntity.getSituationId(),
                newEntity.getTenancyId(),
                newEntity.getDefinition(),
                newEntity.getCreatedAt(),
                newEntity.getUpdatedAt(),
                "runtime"
        );
    }

    /**
     * Delete a runtime situation definition.
     *
     * <p>Only removes the runtime override. If a classpath default exists with
     * the same {@code situationId}, it becomes active again after deletion.
     *
     * @param situationId situation ID
     */
    @DELETE
    @Path("/definitions/{situationId}")
    @RolesAllowed("iot-admin")
    @Transactional
    public void deleteDefinition(@PathParam("situationId") String situationId) {
        int deleted = em.createQuery(
                                "DELETE FROM IoTSituationDefinitionEntity s WHERE s.situationId = :situationId AND s.tenancyId = :tenancyId"
                                    )
                        .setParameter("situationId", situationId)
                        .setParameter("tenancyId", principal.tenancyId())
                        .executeUpdate();

        if (deleted == 0) {
            throw new NotFoundException("Situation definition not found: " + situationId);
        }
    }

    @GET
    @Path("/{situationId}/suggestions")
    @RolesAllowed("iot-viewer")
    public io.casehub.iot.webapp.rest.SituationSuggestionsResponse getSuggestions(
            @PathParam("situationId") String situationId) {
        var terminal = java.util.EnumSet.of(
                io.casehub.api.model.CaseStatus.COMPLETED,
                io.casehub.api.model.CaseStatus.FAULTED,
                io.casehub.api.model.CaseStatus.CANCELLED);

        var activeCases = caseInstanceCache.getAll().stream()
                                           .filter(ci -> !terminal.contains(ci.getState()))
                                           .filter(ci -> situationId.equals(ci.getCaseContext().getString("situationId")))
                                           .toList();

        var caseSuggestions = new java.util.ArrayList<io.casehub.iot.webapp.rest.SituationSuggestionsResponse.CaseSuggestions>();
        for (var ci : activeCases) {
            String                             caseType  = ci.getCaseMetaModel().getName();
            var                                defOpt    = caseDefinitionRegistry.findByName(caseType);
            io.casehub.api.model.cbr.CbrConfig cbrConfig = defOpt.isPresent() ? defOpt.get().getCbrConfig() : null;

            if (cbrConfig == null) {
                caseSuggestions.add(new io.casehub.iot.webapp.rest.SituationSuggestionsResponse.CaseSuggestions(
                        ci.getUuid(), caseType, java.util.List.of()));
                continue;
            }

            try {
                java.util.Map<String, Object> features = new java.util.LinkedHashMap<>();
                for (String key : cbrConfig.weights().keySet()) {
                    Object val = ci.getCaseContext().get(key);
                    if (val != null) {features.put(key, val);}
                }
                var suggestions = retrievalService.retrieve(cbrConfig, features, principal.tenancyId());
                caseSuggestions.add(new io.casehub.iot.webapp.rest.SituationSuggestionsResponse.CaseSuggestions(
                        ci.getUuid(), caseType, suggestions));
            } catch (Exception e) {
                org.jboss.logging.Logger.getLogger(SituationResource.class)
                                        .warnv(e, "CBR retrieval failed for case {0} — skipping", ci.getUuid());
                caseSuggestions.add(new io.casehub.iot.webapp.rest.SituationSuggestionsResponse.CaseSuggestions(
                        ci.getUuid(), caseType, java.util.List.of()));
            }
        }

        return new io.casehub.iot.webapp.rest.SituationSuggestionsResponse(situationId, caseSuggestions);
    }

    /**
     * List active situation contexts with detection history.
     *
     * <p>TODO: Query RAS persistence for active SituationContext records.
     * Requires integration with {@code casehub-ras-persistence-jpa}.
     *
     * @return active situations with confidence levels and detection counts
     */
    @GET
    @Path("/active")
    @RolesAllowed("iot-viewer")
    public List<ActiveSituationResponse> listActive() {
        // TODO: implement once RAS persistence is integrated
        // Query: SELECT * FROM ras_situation_context WHERE tenancy_id = :tenancyId AND terminated_at IS NULL
        return List.of();
    }

    @jakarta.ws.rs.POST
    @jakarta.ws.rs.Path("/active/{correlationKey}/dismiss")
    @jakarta.transaction.Transactional
    public jakarta.ws.rs.core.Response dismissSituation(
            @jakarta.ws.rs.PathParam("correlationKey") String correlationKey,
            io.casehub.iot.webapp.rest.DismissRequest request) {
        if (request.situationId() == null || request.situationId().isBlank()) {
            return jakarta.ws.rs.core.Response.status(400).entity("situationId is required").build();
        }
        String tenancyId = principal.tenancyId();

        var context = situationStore.find(request.situationId(), correlationKey, tenancyId)
                                    .await().indefinitely().orElse(null);

        dismissalRecorder.recordDismissal(
                request.situationId(), correlationKey, tenancyId, context, request.reason());

        if (context != null) {
            situationStore.remove(request.situationId(), correlationKey, tenancyId)
                          .await().indefinitely();
            changeEvent.fireAsync(new io.casehub.ras.api.SituationChangeEvent(
                    tenancyId, request.situationId(), correlationKey,
                    io.casehub.ras.api.SituationChangeEvent.ChangeType.DISMISSED, context));
        }

        return jakarta.ws.rs.core.Response.noContent().build();
    }

    @jakarta.ws.rs.GET
    @jakarta.ws.rs.Path("/suppressions")
    public java.util.List<io.casehub.iot.webapp.rest.SuppressionHistoryResponse> listSuppressions(
            @jakarta.ws.rs.QueryParam("situationId") String situationId,
            @jakarta.ws.rs.QueryParam("since") java.time.Instant since,
            @jakarta.ws.rs.QueryParam("includeOverridden") @jakarta.ws.rs.DefaultValue("false") boolean includeOverridden) {
        String tenancyId = principal.tenancyId();
        if (since == null) {
            since = java.time.Instant.now().minus(java.time.Duration.ofHours(24));
        }

        String jpql = "SELECT s FROM SuppressionLogEntry s WHERE s.tenancyId = :tenancyId AND s.suppressedAt >= :since";
        if (situationId != null) {
            jpql += " AND s.situationId = :situationId";
        }
        if (!includeOverridden) {
            jpql += " AND s.overridden = false";
        }
        jpql += " ORDER BY s.suppressedAt DESC";

        var query = em.createQuery(jpql, io.casehub.iot.webapp.app.persistence.SuppressionLogEntry.class)
                      .setParameter("tenancyId", tenancyId)
                      .setParameter("since", since);
        if (situationId != null) {
            query.setParameter("situationId", situationId);
        }

        return query.getResultList().stream()
                    .map(e -> new io.casehub.iot.webapp.rest.SuppressionHistoryResponse(
                            e.id(), e.situationId(), e.correlationKey(),
                            e.tier().name(), e.dismissalRate(), e.matchedCaseCount(),
                            e.suppressedAt(), e.overridden()))
                    .toList();
    }

    @jakarta.ws.rs.POST
    @jakarta.ws.rs.Path("/suppressions/{id}/override")
    @jakarta.transaction.Transactional
    public jakarta.ws.rs.core.Response overrideSuppression(
            @jakarta.ws.rs.PathParam("id") java.util.UUID id) {
        var entry = em.find(io.casehub.iot.webapp.app.persistence.SuppressionLogEntry.class, id);
        if (entry == null) {
            return jakarta.ws.rs.core.Response.status(404).build();
        }
        if (entry.overridden()) {
            return jakarta.ws.rs.core.Response.status(409).entity("Already overridden").build();
        }

        entry.markOverridden(principal.actorId());

        dismissalRecorder.recordCaseOutcome(
                entry.situationId(), entry.correlationKey(), entry.tenancyId(),
                null, "override-actioned");

        return jakarta.ws.rs.core.Response.ok(java.util.Map.of("overridden", true)).build();
    }

    @jakarta.ws.rs.GET
    @jakarta.ws.rs.Path("/{situationId}/suppression-stats")
    public io.casehub.iot.webapp.rest.SuppressionStatsResponse getSuppressionStats(
            @jakarta.ws.rs.PathParam("situationId") String situationId) {
        String tenancyId = principal.tenancyId();

        var suppressedCount = em.createQuery(
                                        "SELECT COUNT(s) FROM SuppressionLogEntry s WHERE s.tenancyId = :tid AND s.situationId = :sid AND s.tier = :tier",
                                        Long.class)
                                .setParameter("tid", tenancyId)
                                .setParameter("sid", situationId)
                                .setParameter("tier", io.casehub.iot.webapp.cbr.SuppressionTier.SUPPRESS)
                                .getSingleResult().intValue();

        var demotedCount = em.createQuery(
                                     "SELECT COUNT(s) FROM SuppressionLogEntry s WHERE s.tenancyId = :tid AND s.situationId = :sid AND s.tier = :tier",
                                     Long.class)
                             .setParameter("tid", tenancyId)
                             .setParameter("sid", situationId)
                             .setParameter("tier", io.casehub.iot.webapp.cbr.SuppressionTier.DEMOTE)
                             .getSingleResult().intValue();

        var overrideCount = em.createQuery(
                                      "SELECT COUNT(s) FROM SuppressionLogEntry s WHERE s.tenancyId = :tid AND s.situationId = :sid AND s.overridden = true",
                                      Long.class)
                              .setParameter("tid", tenancyId)
                              .setParameter("sid", situationId)
                              .getSingleResult().intValue();

        boolean safetyCritical = io.casehub.iot.webapp.risk.IoTSafetyCaseTypes.SAFETY_SITUATION_IDS.contains(situationId);

        return new io.casehub.iot.webapp.rest.SuppressionStatsResponse(
                situationId, suppressedCount, demotedCount, overrideCount,
                0.0, safetyCritical);
    }


    public record SituationDefinitionResponse(
            String situationId,
            String tenancyId,
            Object definition,
            Instant createdAt,
            Instant updatedAt,
            String source // "classpath" or "runtime"
    ) {
    }

    public record ActiveSituationResponse(
            String situationId,
            String correlationKey,
            double confidence,
            int signalCount,
            Instant firstSignal,
            Instant lastSignal
    ) {
    }
}
