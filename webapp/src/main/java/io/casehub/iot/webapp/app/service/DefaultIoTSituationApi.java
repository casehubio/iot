package io.casehub.iot.webapp.app.service;

import io.casehub.api.model.CaseStatus;
import io.casehub.engine.common.spi.CaseDefinitionRegistry;
import io.casehub.engine.common.spi.cache.CaseInstanceCache;
import io.casehub.iot.webapp.app.persistence.IoTSituationDefinitionEntity;
import io.casehub.iot.webapp.cbr.DismissalRecorder;
import io.casehub.iot.webapp.cbr.IoTCbrRetrievalService;
import io.casehub.iot.webapp.rest.DismissRequest;
import io.casehub.iot.webapp.rest.SituationDefinitionRequest;
import io.casehub.iot.webapp.rest.SituationSuggestionsResponse;
import io.casehub.iot.webapp.spi.IoTSituationApi;
import io.casehub.iot.webapp.view.SituationDefinitionView;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.ras.api.CaseTriggerConfig;
import io.casehub.ras.api.ChainMode;
import io.casehub.ras.api.SituationChangeEvent;
import io.casehub.ras.api.SituationDefinition;
import io.casehub.ras.api.SituationStore;
import io.casehub.ras.api.TriggerAction;
import io.casehub.ras.api.TriggerMode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;

@ApplicationScoped
public class DefaultIoTSituationApi implements IoTSituationApi {

    private static final Logger LOG = Logger.getLogger(DefaultIoTSituationApi.class);

    @Inject EntityManager em;
    @Inject CurrentPrincipal principal;
    @Inject CaseInstanceCache caseInstanceCache;
    @Inject CaseDefinitionRegistry caseDefinitionRegistry;
    @Inject IoTCbrRetrievalService retrievalService;
    @Inject DismissalRecorder dismissalRecorder;
    @Inject SituationStore situationStore;
    @Inject Event<SituationChangeEvent> changeEvent;

    @Override
    public List<SituationDefinitionView> listDefinitions(String tenancyId) {
        var runtimeDefs = em.createQuery(
                        "SELECT s FROM IoTSituationDefinitionEntity s WHERE s.tenancyId = :tenancyId",
                        IoTSituationDefinitionEntity.class)
                .setParameter("tenancyId", tenancyId)
                .getResultList();
        return runtimeDefs.stream()
                .map(def -> new SituationDefinitionView(
                        def.getSituationId(), def.getTenancyId(),
                        def.getDefinition(), def.getCreatedAt(), def.getUpdatedAt(), "runtime"))
                .toList();
    }

    @Override
    @Transactional
    public SituationDefinitionView createDefinition(SituationDefinitionRequest request, String tenancyId) {
        var existing = em.createQuery(
                        "SELECT COUNT(s) FROM IoTSituationDefinitionEntity s WHERE s.situationId = :situationId AND s.tenancyId = :tenancyId",
                        Long.class)
                .setParameter("situationId", request.situationId())
                .setParameter("tenancyId", tenancyId)
                .getSingleResult();
        if (existing > 0) {
            throw new BadRequestException("Situation definition already exists: " + request.situationId());
        }
        var now = Instant.now();
        var definition = mapRequestToDomain(request);
        var entity = new IoTSituationDefinitionEntity(request.situationId(), tenancyId, definition, now, now);
        em.persist(entity);
        return new SituationDefinitionView(
                entity.getSituationId(), entity.getTenancyId(),
                entity.getDefinition(), entity.getCreatedAt(), entity.getUpdatedAt(), "runtime");
    }

    @Override
    @Transactional
    public SituationDefinitionView updateDefinition(String situationId, SituationDefinitionRequest request,
                                                     String tenancyId) {
        var existingEntity = em.createQuery(
                        "SELECT s FROM IoTSituationDefinitionEntity s WHERE s.situationId = :situationId AND s.tenancyId = :tenancyId",
                        IoTSituationDefinitionEntity.class)
                .setParameter("situationId", situationId)
                .setParameter("tenancyId", tenancyId)
                .getResultStream()
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Situation definition not found: " + situationId));
        em.remove(existingEntity);
        em.flush();
        var now = Instant.now();
        var definition = mapRequestToDomain(request);
        var newEntity = new IoTSituationDefinitionEntity(
                request.situationId(), tenancyId, definition, existingEntity.getCreatedAt(), now);
        em.persist(newEntity);
        return new SituationDefinitionView(
                newEntity.getSituationId(), newEntity.getTenancyId(),
                newEntity.getDefinition(), newEntity.getCreatedAt(), newEntity.getUpdatedAt(), "runtime");
    }

    @Override
    @Transactional
    public void deleteDefinition(String situationId, String tenancyId) {
        int deleted = em.createQuery(
                        "DELETE FROM IoTSituationDefinitionEntity s WHERE s.situationId = :situationId AND s.tenancyId = :tenancyId")
                .setParameter("situationId", situationId)
                .setParameter("tenancyId", tenancyId)
                .executeUpdate();
        if (deleted == 0) {
            throw new NotFoundException("Situation definition not found: " + situationId);
        }
    }

    @Override
    public SituationSuggestionsResponse getSuggestions(String situationId, String tenancyId) {
        var terminal = EnumSet.of(CaseStatus.COMPLETED, CaseStatus.FAULTED, CaseStatus.CANCELLED);
        var activeCases = caseInstanceCache.getAll().stream()
                .filter(ci -> !terminal.contains(ci.getState()))
                .filter(ci -> situationId.equals(ci.getCaseContext().getString("situationId")))
                .toList();

        var caseSuggestions = new ArrayList<SituationSuggestionsResponse.CaseSuggestions>();
        for (var ci : activeCases) {
            String caseType = ci.getCaseMetaModel().getName();
            var defOpt = caseDefinitionRegistry.findByName(caseType);
            var cbrConfig = defOpt.isPresent() ? defOpt.get().getCbrConfig() : null;
            if (cbrConfig == null) {
                caseSuggestions.add(new SituationSuggestionsResponse.CaseSuggestions(
                        ci.getUuid(), caseType, List.of()));
                continue;
            }
            try {
                var features = new LinkedHashMap<String, Object>();
                for (String key : cbrConfig.weights().keySet()) {
                    Object val = ci.getCaseContext().get(key);
                    if (val != null) features.put(key, val);
                }
                var suggestions = retrievalService.retrieve(cbrConfig, features, tenancyId);
                caseSuggestions.add(new SituationSuggestionsResponse.CaseSuggestions(
                        ci.getUuid(), caseType, suggestions));
            } catch (Exception e) {
                LOG.warnv(e, "CBR retrieval failed for case {0} — skipping", ci.getUuid());
                caseSuggestions.add(new SituationSuggestionsResponse.CaseSuggestions(
                        ci.getUuid(), caseType, List.of()));
            }
        }
        return new SituationSuggestionsResponse(situationId, caseSuggestions);
    }

    @Override
    @Transactional
    public void dismissSituation(String correlationKey, DismissRequest request, String tenancyId) {
        if (request.situationId() == null || request.situationId().isBlank()) {
            throw new BadRequestException("situationId is required");
        }
        var context = situationStore.find(request.situationId(), correlationKey, tenancyId).orElse(null);
        dismissalRecorder.recordDismissal(
                request.situationId(), correlationKey, tenancyId, context, request.reason());
        if (context != null) {
            situationStore.remove(request.situationId(), correlationKey, tenancyId);
            changeEvent.fireAsync(new SituationChangeEvent(
                    tenancyId, request.situationId(), correlationKey,
                    SituationChangeEvent.ChangeType.DISMISSED, context));
        }
    }

    private static SituationDefinition mapRequestToDomain(SituationDefinitionRequest request) {
        var chainMode = mapChainMode(request.chainMode());
        var triggerMode = mapTriggerMode(request.triggerMode());
        var triggerConfig = new CaseTriggerConfig(
                request.triggerConfig().caseNamespace(),
                request.triggerConfig().caseName(),
                request.triggerConfig().caseVersion(),
                request.triggerConfig().baseCaseData());
        return new SituationDefinition(
                request.situationId(), request.eventTypes(),
                request.correlationWindow(), request.eventBufferDelay(),
                chainMode, new TriggerAction.CreateCase(triggerConfig), triggerMode);
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
        if (request == null) return new TriggerMode.FireOnce();
        return switch (request.type()) {
            case "fire_once" -> new TriggerMode.FireOnce();
            case "repeating" -> new TriggerMode.Repeating(request.cooldown());
            default -> throw new BadRequestException("Unknown trigger mode type: " + request.type());
        };
    }
}
