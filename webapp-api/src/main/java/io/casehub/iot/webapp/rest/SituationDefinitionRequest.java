package io.casehub.iot.webapp.rest;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * REST request record for create/update situation definitions.
 *
 * <p>Used by {@code POST /api/situations/definitions} and
 * {@code PUT /api/situations/definitions/{situationId}}.
 */
public record SituationDefinitionRequest(
        String situationId,
        Set<String> eventTypes,
        Duration correlationWindow,
        Duration eventBufferDelay,
        ChainModeRequest chainMode,
        TriggerModeRequest triggerMode,
        CaseTriggerConfigRequest triggerConfig
) {

    public record ChainModeRequest(
            String type,
            Set<String> ganglia,
            Double minConfidence,
            String ganglionId,
            Integer requiredCount
    ) {
    }

    public record TriggerModeRequest(
            String type,
            Duration cooldown
    ) {
    }

    public record CaseTriggerConfigRequest(
            String caseNamespace,
            String caseName,
            String caseVersion,
            Map<String, Object> baseCaseData
    ) {
    }
}
