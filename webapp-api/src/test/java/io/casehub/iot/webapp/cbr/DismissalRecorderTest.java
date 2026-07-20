package io.casehub.iot.webapp.cbr;

import io.casehub.iot.api.DeviceClass;
import io.casehub.iot.api.SwitchDevice;
import io.casehub.iot.api.spi.DeviceRegistry;
import io.casehub.neocortex.memory.cbr.CbrCase;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.FeatureVectorCbrCase;
import io.casehub.ras.api.DetectionResult;
import io.casehub.ras.api.DetectionSignal;
import io.casehub.ras.api.SituationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DismissalRecorderTest {

    private CbrCaseMemoryStore store;
    private DeviceRegistry deviceRegistry;
    private DismissalRecorder recorder;

    private static final Instant NOW = Instant.parse("2026-07-20T14:00:00Z");

    @BeforeEach
    void setUp() {
        store = mock(CbrCaseMemoryStore.class);
        deviceRegistry = mock(DeviceRegistry.class);
        recorder = new DismissalRecorder(store, deviceRegistry);
        when(store.store(any(), any(), any(), any(), any(), any(), any())).thenReturn("case-1");
    }

    private SituationContext contextWithDetections() {
        return SituationContext.initial("sit-1", "device/thermostat-1", "t1", NOW)
                .withDetection(new DetectionResult("g1", 0.8, DetectionSignal.DETECTED, Map.of()), NOW)
                .withDetection(new DetectionResult("g2", 0.9, DetectionSignal.ANTI, Map.of()), NOW);
    }

    private SwitchDevice testDevice() {
        return SwitchDevice.builder()
                .deviceId("device/thermostat-1")
                .deviceClass(DeviceClass.THERMOSTAT)
                .label("Living Room Thermostat")
                .tenancyId("t1")
                .providerId("ha")
                .location("living_room")
                .lastUpdated(NOW)
                .build();
    }

    @Test
    void recordDismissal_withContext_storesCbrCaseWithDismissedOutcome() {
        when(deviceRegistry.findById("device/thermostat-1")).thenReturn(Optional.of(testDevice()));
        var context = contextWithDetections();

        recorder.recordDismissal("sit-1", "device/thermostat-1", "t1", context, "not actionable");

        var caseCaptor = ArgumentCaptor.forClass(CbrCase.class);
        verify(store).store(caseCaptor.capture(), eq("iot-dismissal:sit-1"),
                eq("device/thermostat-1"), any(), eq("t1"), any(), any());

        var cbrCase = (FeatureVectorCbrCase) caseCaptor.getValue();
        assertThat(cbrCase.outcome()).isEqualTo("dismissed");
        assertThat(cbrCase.problem()).contains("sit-1");
    }

    @Test
    void recordDismissal_withContext_extractsFeaturesFromDevice() {
        when(deviceRegistry.findById("device/thermostat-1")).thenReturn(Optional.of(testDevice()));
        var context = contextWithDetections();

        recorder.recordDismissal("sit-1", "device/thermostat-1", "t1", context, null);

        var caseCaptor = ArgumentCaptor.forClass(CbrCase.class);
        verify(store).store(caseCaptor.capture(), any(), any(), any(), any(), any(), any());

        var cbrCase = (FeatureVectorCbrCase) caseCaptor.getValue();
        assertThat(cbrCase.features()).containsKey("deviceClass");
        assertThat(cbrCase.features()).containsKey("roomType");
        assertThat(cbrCase.features()).containsKey("hourOfDay");
        assertThat(cbrCase.features()).containsKey("detectionConfidence");
    }

    @Test
    void recordDismissal_withContext_detectionConfidenceExcludesAnti() {
        when(deviceRegistry.findById("device/thermostat-1")).thenReturn(Optional.of(testDevice()));
        var context = contextWithDetections();

        recorder.recordDismissal("sit-1", "device/thermostat-1", "t1", context, null);

        var caseCaptor = ArgumentCaptor.forClass(CbrCase.class);
        verify(store).store(caseCaptor.capture(), any(), any(), any(), any(), any(), any());

        var cbrCase = (FeatureVectorCbrCase) caseCaptor.getValue();
        var rawFeatures = io.casehub.neocortex.memory.cbr.FeatureValue.toRawMap(cbrCase.features());
        assertThat((double) rawFeatures.get("detectionConfidence")).isEqualTo(0.8);
    }

    @Test
    void recordDismissal_noContext_storesCbrCaseWithDegradedFeatures() {
        when(deviceRegistry.findById("device/thermostat-1")).thenReturn(Optional.of(testDevice()));

        recorder.recordDismissal("sit-1", "device/thermostat-1", "t1", null, "false alarm");

        var caseCaptor = ArgumentCaptor.forClass(CbrCase.class);
        verify(store).store(caseCaptor.capture(), eq("iot-dismissal:sit-1"),
                any(), any(), eq("t1"), any(), any());

        var cbrCase = (FeatureVectorCbrCase) caseCaptor.getValue();
        assertThat(cbrCase.outcome()).isEqualTo("dismissed");
        assertThat(cbrCase.features()).containsKey("deviceClass");
        assertThat(cbrCase.features()).containsKey("hourOfDay");
        var rawFeatures = io.casehub.neocortex.memory.cbr.FeatureValue.toRawMap(cbrCase.features());
        assertThat((double) rawFeatures.get("detectionConfidence")).isEqualTo(0.0);
    }

    @Test
    void recordDismissal_deviceNotFound_omitsDeviceFeatures() {
        when(deviceRegistry.findById("device/thermostat-1")).thenReturn(Optional.empty());
        var context = contextWithDetections();

        recorder.recordDismissal("sit-1", "device/thermostat-1", "t1", context, null);

        var caseCaptor = ArgumentCaptor.forClass(CbrCase.class);
        verify(store).store(caseCaptor.capture(), any(), any(), any(), any(), any(), any());

        var cbrCase = (FeatureVectorCbrCase) caseCaptor.getValue();
        assertThat(cbrCase.features()).doesNotContainKey("deviceClass");
        assertThat(cbrCase.features()).doesNotContainKey("roomType");
        assertThat(cbrCase.features()).containsKey("hourOfDay");
    }

    @Test
    void recordCaseOutcome_actioned_storesActionedOutcome() {
        when(deviceRegistry.findById("device/thermostat-1")).thenReturn(Optional.empty());
        var context = contextWithDetections();

        recorder.recordCaseOutcome("sit-1", "device/thermostat-1", "t1", context, "actioned");

        var caseCaptor = ArgumentCaptor.forClass(CbrCase.class);
        verify(store).store(caseCaptor.capture(), eq("iot-dismissal:sit-1"),
                any(), any(), eq("t1"), any(), any());

        var cbrCase = (FeatureVectorCbrCase) caseCaptor.getValue();
        assertThat(cbrCase.outcome()).isEqualTo("actioned");
    }

    @Test
    void recordCaseOutcome_overrideActioned_storesOverrideOutcome() {
        when(deviceRegistry.findById("device/thermostat-1")).thenReturn(Optional.empty());
        var context = contextWithDetections();

        recorder.recordCaseOutcome("sit-1", "device/thermostat-1", "t1", context, "override-actioned");

        var caseCaptor = ArgumentCaptor.forClass(CbrCase.class);
        verify(store).store(caseCaptor.capture(), any(), any(), any(), any(), any(), any());

        var cbrCase = (FeatureVectorCbrCase) caseCaptor.getValue();
        assertThat(cbrCase.outcome()).isEqualTo("override-actioned");
    }

    @Test
    void constructor_nullStoreThrows() {
        assertThatNullPointerException()
                .isThrownBy(() -> new DismissalRecorder(null, deviceRegistry));
    }

    @Test
    void constructor_nullDeviceRegistryThrows() {
        assertThatNullPointerException()
                .isThrownBy(() -> new DismissalRecorder(store, null));
    }
}
