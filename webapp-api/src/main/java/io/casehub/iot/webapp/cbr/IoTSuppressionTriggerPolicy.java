package io.casehub.iot.webapp.cbr;

import io.casehub.iot.api.spi.DeviceRegistry;
import io.casehub.iot.webapp.risk.IoTSafetyCaseTypes;
import io.casehub.ras.api.DetectionSignal;
import io.casehub.ras.api.PolicyDecision;
import io.casehub.ras.api.RasTriggerPolicy;
import io.casehub.ras.api.SituationContext;
import io.casehub.ras.api.SituationDefinition;
import io.casehub.ras.api.SuppressionMetadataKeys;
import io.casehub.ras.api.TriggerAction;
import io.casehub.ras.api.TriggerDecision;
import io.casehub.ras.runtime.DefaultRasTriggerPolicy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public class IoTSuppressionTriggerPolicy implements RasTriggerPolicy {

    private final DefaultRasTriggerPolicy delegate = new DefaultRasTriggerPolicy();
    private final SuppressionEvaluator suppressionEvaluator;
    private final DeviceRegistry deviceRegistry;

    public IoTSuppressionTriggerPolicy(SuppressionEvaluator suppressionEvaluator,
                                        DeviceRegistry deviceRegistry) {
        this.suppressionEvaluator = Objects.requireNonNull(suppressionEvaluator, "suppressionEvaluator");
        this.deviceRegistry = Objects.requireNonNull(deviceRegistry, "deviceRegistry");
    }

    @Override
    public PolicyDecision evaluate(SituationContext context, SituationDefinition definition) {
        PolicyDecision base = delegate.evaluate(context, definition);
        if (base.decision() != TriggerDecision.TRIGGER
            && base.decision() != TriggerDecision.TRIGGER_AND_CONTINUE) {
            return base;
        }
        if (isSafetyCritical(definition)) {
            return base;
        }

        Map<String, Object> features = extractFeatures(context);
        SuppressionAssessment assessment = suppressionEvaluator.assess(
                definition.situationId(), features, context.tenancyId());

        return switch (assessment.tier()) {
            case NONE -> base;
            case ANNOTATE -> new PolicyDecision(base.decision(), buildMetadata(assessment, "annotate"));
            case DEMOTE -> new PolicyDecision(TriggerDecision.SUPPRESS, buildMetadata(assessment, "demote"));
            case SUPPRESS -> new PolicyDecision(TriggerDecision.SUPPRESS, buildMetadata(assessment, "full"));
        };
    }

    private boolean isSafetyCritical(SituationDefinition definition) {
        if (IoTSafetyCaseTypes.SAFETY_SITUATION_IDS.contains(definition.situationId())) {
            return true;
        }
        return definition.triggerAction() instanceof TriggerAction.CreateCase createCase
                && IoTSafetyCaseTypes.SAFETY_CASE_TYPES.contains(createCase.config().caseName());
    }

    private Map<String, Object> extractFeatures(SituationContext context) {
        var features = new LinkedHashMap<String, Object>();
        var device = deviceRegistry.findById(context.correlationKey());
        device.ifPresent(d -> {
            features.put("deviceClass", d.deviceClass().name().toLowerCase());
            if (d.location() != null) {
                features.put("roomType", d.location());
            }
        });
        IoTCbrFeatureExtractors.deriveTemporalFeatures(features, context.lastSignal());
        double maxConfidence = context.detections().stream()
                .filter(td -> td.result().signal().isAtLeast(DetectionSignal.WEAK))
                .mapToDouble(td -> td.result().confidence())
                .max().orElse(0.0);
        features.put("detectionConfidence", maxConfidence);
        return Map.copyOf(features);
    }

    private static Map<String, Object> buildMetadata(SuppressionAssessment assessment, String tierLabel) {
        return Map.of(
                SuppressionMetadataKeys.TIER, tierLabel,
                SuppressionMetadataKeys.DISMISSAL_RATE, assessment.dismissalRate(),
                SuppressionMetadataKeys.MATCH_COUNT, assessment.totalCases(),
                SuppressionMetadataKeys.AVERAGE_SIMILARITY, assessment.averageSimilarity());
    }
}
