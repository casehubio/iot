package io.casehub.iot.webapp.cbr;

import io.casehub.iot.api.DeviceClass;
import io.casehub.iot.api.DeviceEntity;
import io.casehub.iot.api.SwitchDevice;
import io.casehub.iot.api.spi.DeviceRegistry;
import io.casehub.ras.api.CaseTriggerConfig;
import io.casehub.ras.api.ChainMode;
import io.casehub.ras.api.DetectionResult;
import io.casehub.ras.api.DetectionSignal;
import io.casehub.ras.api.PolicyDecision;
import io.casehub.ras.api.SituationContext;
import io.casehub.ras.api.SituationDefinition;
import io.casehub.ras.api.SuppressionMetadataKeys;
import io.casehub.ras.api.TriggerAction;
import io.casehub.ras.api.TriggerDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class IoTSuppressionTriggerPolicyTest {

    private SuppressionEvaluator suppressionEvaluator;
    private DeviceRegistry deviceRegistry;
    private IoTSuppressionTriggerPolicy policy;

    private static final CaseTriggerConfig NORMAL_CONFIG =
            new CaseTriggerConfig("ns", "hvac-anomaly", "1.0", Map.of());
    private static final CaseTriggerConfig SAFETY_CONFIG =
            new CaseTriggerConfig("ns", "safety-alert", "1.0", Map.of());
    private static final Instant NOW = Instant.parse("2026-07-20T14:00:00Z");

    @BeforeEach
    void setUp() {
        suppressionEvaluator = mock(SuppressionEvaluator.class);
        deviceRegistry = mock(DeviceRegistry.class);
        policy = new IoTSuppressionTriggerPolicy(suppressionEvaluator, deviceRegistry);
    }

    private SituationContext ctx(String situationId) {
        return SituationContext.initial(situationId, "device/thermostat-1", "t1", NOW);
    }

    private SituationContext ctxWithDetection(String situationId) {
        return ctx(situationId).withDetection(
                new DetectionResult("g1", 0.9, DetectionSignal.DETECTED, Map.of()), NOW);
    }

    private SituationDefinition def(String situationId, TriggerAction action) {
        return new SituationDefinition(situationId, Set.of("temp.reading"),
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1")),
                action, null);
    }

    @Test
    void continueAccumulating_passedThrough_noSuppressionCheck() {
        var context = ctx("sit-1");
        var definition = def("sit-1", new TriggerAction.CreateCase(NORMAL_CONFIG));

        var result = policy.evaluate(context, definition).await().indefinitely();

        assertThat(result.decision()).isEqualTo(TriggerDecision.CONTINUE_ACCUMULATING);
        verifyNoInteractions(suppressionEvaluator);
    }

    @Test
    void safetyCriticalCaseType_neverSuppressed() {
        var context = ctxWithDetection("sit-1");
        var definition = def("sit-1", new TriggerAction.CreateCase(SAFETY_CONFIG));

        var result = policy.evaluate(context, definition).await().indefinitely();

        assertThat(result.decision()).isEqualTo(TriggerDecision.TRIGGER);
        verifyNoInteractions(suppressionEvaluator);
    }

    @Test
    void safetySituationId_neverSuppressed() {
        var context = ctxWithDetection("smoke-detected");
        var definition = def("smoke-detected", new TriggerAction.NotifyOnly());

        var result = policy.evaluate(context, definition).await().indefinitely();

        assertThat(result.decision()).isEqualTo(TriggerDecision.TRIGGER);
        verifyNoInteractions(suppressionEvaluator);
    }

    @Test
    void triggerWithHighDismissalRate_returnsSuppressWithMetadata() {
        var context = ctxWithDetection("sit-1");
        var definition = def("sit-1", new TriggerAction.CreateCase(NORMAL_CONFIG));

        when(deviceRegistry.findById("device/thermostat-1")).thenReturn(Optional.empty());
        when(suppressionEvaluator.assess(eq("sit-1"), any(), eq("t1")))
                .thenReturn(new SuppressionAssessment(
                        SuppressionTier.SUPPRESS, 0.92, 15, 14, 0.85));

        var result = policy.evaluate(context, definition).await().indefinitely();

        assertThat(result.decision()).isEqualTo(TriggerDecision.SUPPRESS);
        assertThat(result.metadata())
                .containsEntry(SuppressionMetadataKeys.TIER, "full")
                .containsEntry(SuppressionMetadataKeys.DISMISSAL_RATE, 0.92);
    }

    @Test
    void triggerWithModerateDismissalRate_returnsSuppressWithDemoteMetadata() {
        var context = ctxWithDetection("sit-1");
        var definition = def("sit-1", new TriggerAction.CreateCase(NORMAL_CONFIG));

        when(deviceRegistry.findById("device/thermostat-1")).thenReturn(Optional.empty());
        when(suppressionEvaluator.assess(eq("sit-1"), any(), eq("t1")))
                .thenReturn(new SuppressionAssessment(
                        SuppressionTier.DEMOTE, 0.78, 12, 9, 0.72));

        var result = policy.evaluate(context, definition).await().indefinitely();

        assertThat(result.decision()).isEqualTo(TriggerDecision.SUPPRESS);
        assertThat(result.metadata())
                .containsEntry(SuppressionMetadataKeys.TIER, "demote");
    }

    @Test
    void triggerWithLowDismissalRate_returnsTriggerWithAnnotation() {
        var context = ctxWithDetection("sit-1");
        var definition = def("sit-1", new TriggerAction.CreateCase(NORMAL_CONFIG));

        when(deviceRegistry.findById("device/thermostat-1")).thenReturn(Optional.empty());
        when(suppressionEvaluator.assess(eq("sit-1"), any(), eq("t1")))
                .thenReturn(new SuppressionAssessment(
                        SuppressionTier.ANNOTATE, 0.45, 10, 5, 0.65));

        var result = policy.evaluate(context, definition).await().indefinitely();

        assertThat(result.decision()).isEqualTo(TriggerDecision.TRIGGER);
        assertThat(result.metadata())
                .containsEntry(SuppressionMetadataKeys.TIER, "annotate")
                .containsEntry(SuppressionMetadataKeys.DISMISSAL_RATE, 0.45);
    }

    @Test
    void triggerWithNoHistory_returnsTriggerNoMetadata() {
        var context = ctxWithDetection("sit-1");
        var definition = def("sit-1", new TriggerAction.CreateCase(NORMAL_CONFIG));

        when(deviceRegistry.findById("device/thermostat-1")).thenReturn(Optional.empty());
        when(suppressionEvaluator.assess(eq("sit-1"), any(), eq("t1")))
                .thenReturn(new SuppressionAssessment(
                        SuppressionTier.NONE, 0.0, 0, 0, 0.0));

        var result = policy.evaluate(context, definition).await().indefinitely();

        assertThat(result.decision()).isEqualTo(TriggerDecision.TRIGGER);
        assertThat(result.metadata()).isEmpty();
    }

    @Test
    void featureExtraction_usesDeviceRegistry() {
        var context = ctxWithDetection("sit-1");
        var definition = def("sit-1", new TriggerAction.CreateCase(NORMAL_CONFIG));

        var device = SwitchDevice.builder()
                .deviceId("device/thermostat-1")
                .deviceClass(DeviceClass.THERMOSTAT)
                .label("Living Room Thermostat")
                .tenancyId("t1")
                .providerId("ha")
                .location("living_room")
                .lastUpdated(NOW)
                .build();
        when(deviceRegistry.findById("device/thermostat-1")).thenReturn(Optional.of(device));
        when(suppressionEvaluator.assess(eq("sit-1"), any(), eq("t1")))
                .thenReturn(new SuppressionAssessment(SuppressionTier.NONE, 0, 0, 0, 0));

        policy.evaluate(context, definition).await().indefinitely();

        var captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        org.mockito.Mockito.verify(suppressionEvaluator).assess(eq("sit-1"), captor.capture(), eq("t1"));

        @SuppressWarnings("unchecked")
        Map<String, Object> features = captor.getValue();
        assertThat(features).containsEntry("deviceClass", "thermostat");
        assertThat(features).containsEntry("roomType", "living_room");
        assertThat(features).containsKey("hourOfDay");
        assertThat(features).containsKey("dayType");
        assertThat(features).containsKey("season");
        assertThat(features).containsKey("detectionConfidence");
    }

    @Test
    void featureExtraction_deviceNotFound_omitsDeviceFeatures() {
        var context = ctxWithDetection("sit-1");
        var definition = def("sit-1", new TriggerAction.CreateCase(NORMAL_CONFIG));

        when(deviceRegistry.findById("device/thermostat-1")).thenReturn(Optional.empty());
        when(suppressionEvaluator.assess(eq("sit-1"), any(), eq("t1")))
                .thenReturn(new SuppressionAssessment(SuppressionTier.NONE, 0, 0, 0, 0));

        policy.evaluate(context, definition).await().indefinitely();

        var captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        org.mockito.Mockito.verify(suppressionEvaluator).assess(eq("sit-1"), captor.capture(), eq("t1"));

        @SuppressWarnings("unchecked")
        Map<String, Object> features = captor.getValue();
        assertThat(features).doesNotContainKey("deviceClass");
        assertThat(features).doesNotContainKey("roomType");
        assertThat(features).containsKey("hourOfDay");
    }

    @Test
    void detectionConfidence_excludesAntiSignals() {
        var context = ctx("sit-1")
                .withDetection(new DetectionResult("g1", 0.9, DetectionSignal.ANTI, Map.of()), NOW)
                .withDetection(new DetectionResult("g2", 0.6, DetectionSignal.DETECTED, Map.of()), NOW);
        var definition = new SituationDefinition("sit-1", Set.of("temp.reading"),
                Duration.ofMinutes(5), null, new ChainMode.Or(Set.of("g1", "g2")),
                new TriggerAction.CreateCase(NORMAL_CONFIG), null);

        when(deviceRegistry.findById("device/thermostat-1")).thenReturn(Optional.empty());
        when(suppressionEvaluator.assess(eq("sit-1"), any(), eq("t1")))
                .thenReturn(new SuppressionAssessment(SuppressionTier.NONE, 0, 0, 0, 0));

        policy.evaluate(context, definition).await().indefinitely();

        var captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        org.mockito.Mockito.verify(suppressionEvaluator).assess(eq("sit-1"), captor.capture(), eq("t1"));

        @SuppressWarnings("unchecked")
        Map<String, Object> features = captor.getValue();
        assertThat((double) features.get("detectionConfidence")).isEqualTo(0.6);
    }
}
