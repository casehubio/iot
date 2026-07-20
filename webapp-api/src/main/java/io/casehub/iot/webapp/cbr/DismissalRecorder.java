package io.casehub.iot.webapp.cbr;

import io.casehub.iot.api.spi.DeviceRegistry;
import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.FeatureValue;
import io.casehub.neocortex.memory.cbr.FeatureVectorCbrCase;
import io.casehub.platform.api.path.Path;
import io.casehub.ras.api.DetectionSignal;
import io.casehub.ras.api.SituationContext;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class DismissalRecorder {

    private static final MemoryDomain IOT_DOMAIN = new MemoryDomain("iot");

    private final CbrCaseMemoryStore store;
    private final DeviceRegistry deviceRegistry;

    public DismissalRecorder(CbrCaseMemoryStore store, DeviceRegistry deviceRegistry) {
        this.store = Objects.requireNonNull(store, "store");
        this.deviceRegistry = Objects.requireNonNull(deviceRegistry, "deviceRegistry");
    }

    public void recordDismissal(String situationId, String correlationKey, String tenancyId,
                                 SituationContext context, String reason) {
        Map<String, Object> rawFeatures = extractFeatures(correlationKey, context);
        String problemDesc = "Situation '" + situationId + "' dismissed"
                             + (reason != null ? ": " + reason : "");
        storeCase(situationId, correlationKey, tenancyId, rawFeatures, problemDesc, "dismissed");
    }

    public void recordCaseOutcome(String situationId, String correlationKey, String tenancyId,
                                   SituationContext context, String outcome) {
        Map<String, Object> rawFeatures = extractFeatures(correlationKey, context);
        String problemDesc = "Situation '" + situationId + "' case resolved as " + outcome;
        storeCase(situationId, correlationKey, tenancyId, rawFeatures, problemDesc, outcome);
    }

    private Map<String, Object> extractFeatures(String correlationKey, SituationContext context) {
        var features = new LinkedHashMap<String, Object>();

        var device = deviceRegistry.findById(correlationKey);
        device.ifPresent(d -> {
            features.put("deviceClass", d.deviceClass().name().toLowerCase());
            if (d.location() != null) {
                features.put("roomType", d.location());
            }
        });

        Instant eventTime = context != null ? context.lastSignal() : Instant.now();
        IoTCbrFeatureExtractors.deriveTemporalFeatures(features, eventTime);

        double maxConfidence = 0.0;
        if (context != null) {
            maxConfidence = context.detections().stream()
                    .filter(td -> td.result().signal().isAtLeast(DetectionSignal.WEAK))
                    .mapToDouble(td -> td.result().confidence())
                    .max().orElse(0.0);
        }
        features.put("detectionConfidence", maxConfidence);

        return Map.copyOf(features);
    }

    private void storeCase(String situationId, String correlationKey, String tenancyId,
                            Map<String, Object> rawFeatures, String problem, String outcome) {
        Map<String, FeatureValue> featureMap = FeatureValue.toFeatureMap(rawFeatures);
        var cbrCase = new FeatureVectorCbrCase(problem, "operator-feedback", outcome, null, featureMap);
        String caseType = "iot-dismissal:" + situationId;
        store.store(cbrCase, caseType, correlationKey, IOT_DOMAIN, tenancyId,
                UUID.randomUUID().toString(), Path.root());
    }
}
