package io.casehub.iot.webapp.app.cbr;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.iot.webapp.app.persistence.SuppressionLogEntry;
import io.casehub.iot.webapp.cbr.SuppressionTier;
import io.casehub.ras.api.SituationChangeEvent;
import io.casehub.ras.api.SuppressionMetadataKeys;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.logging.Logger;

@ApplicationScoped
public class SuppressionLogObserver {

    private static final Logger LOG = Logger.getLogger(SuppressionLogObserver.class.getName());

    @Inject
    EntityManager em;

    @Inject
    ObjectMapper objectMapper;

    @Transactional
    public void onSuppressed(@ObservesAsync SituationChangeEvent event) {
        if (event.changeType() != SituationChangeEvent.ChangeType.SUPPRESSED) {
            return;
        }

        var metadata = event.metadata();
        String tierStr = (String) metadata.getOrDefault(SuppressionMetadataKeys.TIER, "full");
        SuppressionTier tier = switch (tierStr) {
            case "demote" -> SuppressionTier.DEMOTE;
            default -> SuppressionTier.SUPPRESS;
        };

        double dismissalRate = metadata.containsKey(SuppressionMetadataKeys.DISMISSAL_RATE)
                ? ((Number) metadata.get(SuppressionMetadataKeys.DISMISSAL_RATE)).doubleValue()
                : 0.0;
        int matchCount = metadata.containsKey(SuppressionMetadataKeys.MATCH_COUNT)
                ? ((Number) metadata.get(SuppressionMetadataKeys.MATCH_COUNT)).intValue()
                : 0;
        double avgSimilarity = metadata.containsKey(SuppressionMetadataKeys.AVERAGE_SIMILARITY)
                ? ((Number) metadata.get(SuppressionMetadataKeys.AVERAGE_SIMILARITY)).doubleValue()
                : 0.0;

        String contextJson = null;
        try {
            contextJson = objectMapper.writeValueAsString(event.context());
        } catch (JsonProcessingException e) {
            LOG.warning("Failed to serialize SituationContext for suppression log: " + e.getMessage());
        }

        var entry = new SuppressionLogEntry(
                event.situationId(), event.correlationKey(), event.tenancyId(),
                Instant.now(), tier, dismissalRate, matchCount, avgSimilarity, contextJson);

        em.persist(entry);
    }
}
