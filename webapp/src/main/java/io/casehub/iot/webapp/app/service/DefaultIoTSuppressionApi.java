package io.casehub.iot.webapp.app.service;

import io.casehub.iot.webapp.app.persistence.SuppressionLogEntry;
import io.casehub.iot.webapp.cbr.DismissalRecorder;
import io.casehub.iot.webapp.cbr.SuppressionTier;
import io.casehub.iot.webapp.rest.SuppressionHistoryResponse;
import io.casehub.iot.webapp.rest.SuppressionStatsResponse;
import io.casehub.iot.webapp.risk.IoTSafetyCaseTypes;
import io.casehub.iot.webapp.spi.IoTSuppressionApi;
import io.casehub.platform.api.identity.CurrentPrincipal;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class DefaultIoTSuppressionApi implements IoTSuppressionApi {

    @Inject EntityManager em;
    @Inject DismissalRecorder dismissalRecorder;
    @Inject CurrentPrincipal principal;

    @Override
    public List<SuppressionHistoryResponse> listSuppressions(String situationId, Instant since,
                                                              Boolean includeOverridden,
                                                              String tenancyId) {
        Instant effectiveSince = since != null ? since : Instant.now().minus(Duration.ofHours(24));
        boolean includeOvr = includeOverridden != null && includeOverridden;

        String jpql = "SELECT s FROM SuppressionLogEntry s WHERE s.tenancyId = :tenancyId AND s.suppressedAt >= :since";
        if (situationId != null) {
            jpql += " AND s.situationId = :situationId";
        }
        if (!includeOvr) {
            jpql += " AND s.overridden = false";
        }
        jpql += " ORDER BY s.suppressedAt DESC";

        var query = em.createQuery(jpql, SuppressionLogEntry.class)
                .setParameter("tenancyId", tenancyId)
                .setParameter("since", effectiveSince);
        if (situationId != null) {
            query.setParameter("situationId", situationId);
        }

        return query.getResultList().stream()
                .map(e -> new SuppressionHistoryResponse(
                        e.id(), e.situationId(), e.correlationKey(),
                        e.tier().name(), e.dismissalRate(), e.matchedCaseCount(),
                        e.suppressedAt(), e.overridden()))
                .toList();
    }

    @Override
    @Transactional
    public void overrideSuppression(UUID id, String tenancyId) {
        var entry = em.find(SuppressionLogEntry.class, id);
        if (entry == null) {
            throw new NotFoundException("Suppression not found: " + id);
        }
        if (entry.overridden()) {
            throw new jakarta.ws.rs.ClientErrorException("Already overridden", 409);
        }
        entry.markOverridden(principal.actorId());
        dismissalRecorder.recordCaseOutcome(
                entry.situationId(), entry.correlationKey(), entry.tenancyId(),
                null, "override-actioned");
    }

    @Override
    public SuppressionStatsResponse getSuppressionStats(String situationId, String tenancyId) {
        var suppressedCount = em.createQuery(
                        "SELECT COUNT(s) FROM SuppressionLogEntry s WHERE s.tenancyId = :tid AND s.situationId = :sid AND s.tier = :tier",
                        Long.class)
                .setParameter("tid", tenancyId)
                .setParameter("sid", situationId)
                .setParameter("tier", SuppressionTier.SUPPRESS)
                .getSingleResult().intValue();

        var demotedCount = em.createQuery(
                        "SELECT COUNT(s) FROM SuppressionLogEntry s WHERE s.tenancyId = :tid AND s.situationId = :sid AND s.tier = :tier",
                        Long.class)
                .setParameter("tid", tenancyId)
                .setParameter("sid", situationId)
                .setParameter("tier", SuppressionTier.DEMOTE)
                .getSingleResult().intValue();

        var overrideCount = em.createQuery(
                        "SELECT COUNT(s) FROM SuppressionLogEntry s WHERE s.tenancyId = :tid AND s.situationId = :sid AND s.overridden = true",
                        Long.class)
                .setParameter("tid", tenancyId)
                .setParameter("sid", situationId)
                .getSingleResult().intValue();

        boolean safetyCritical = IoTSafetyCaseTypes.SAFETY_SITUATION_IDS.contains(situationId);

        return new SuppressionStatsResponse(
                situationId, suppressedCount, demotedCount, overrideCount,
                0.0, safetyCritical);
    }
}
