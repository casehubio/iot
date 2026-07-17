package io.casehub.iot.webapp.app.engine;

import io.casehub.iot.api.DeviceClass;
import io.casehub.iot.api.DeviceEntity;
import io.casehub.iot.api.SwitchDevice;
import io.casehub.iot.testing.MockDeviceRegistry;
import io.casehub.ras.api.CaseTriggerConfig;
import io.casehub.ras.api.SituationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IoTCaseInputContributorTest {

    private static final Instant T1 = Instant.parse("2026-07-13T14:30:00Z");

    private MockDeviceRegistry registry;
    private IoTCaseInputContributor contributor;

    @BeforeEach
    void setUp() {
        registry = new MockDeviceRegistry();
        contributor = new IoTCaseInputContributor(registry);
    }

    @Test
    void contributesDeviceMetadataFromRegistry() {
        DeviceEntity device = SwitchDevice.builder()
                                          .deviceId("switch.living_room")
                                          .deviceClass(DeviceClass.SWITCH)
                                          .label("Living Room Switch")
                                          .available(true)
                                          .lastUpdated(T1)
                                          .tenancyId("t1")
                                          .providerId("homeassistant")
                                          .location("Living Room")
                                          .on(true)
                                          .build();
        registry.addDevice(device);

        var config = new CaseTriggerConfig("io.casehub.iot", "generic-response", "1.0", Map.of());
        var ctx    = SituationContext.initial("device-offline", "device/switch.living_room", "t1", T1);

        Map<String, Object> result = contributor.contribute(config, ctx);

        assertThat(result.get("situationId")).isEqualTo("device-offline");
        assertThat(result.get("deviceId")).isEqualTo("switch.living_room");
        assertThat(result.get("deviceClass")).isEqualTo("switch");
        assertThat(result.get("roomType")).isEqualTo("Living Room");
        assertThat(result.get("eventTimestamp")).isEqualTo(T1);
    }

    @Test
    void omitsRoomTypeWhenLocationNull() {
        DeviceEntity device = SwitchDevice.builder()
                .deviceId("switch.garage")
                .deviceClass(DeviceClass.SWITCH)
                .label("Garage Switch")
                .available(true)
                .lastUpdated(T1)
                .tenancyId("t1")
                .providerId("homeassistant")
                .on(false)
                .build();
        registry.addDevice(device);

        var config = new CaseTriggerConfig("io.casehub.iot", "generic-response", "1.0", Map.of());
        var ctx = SituationContext.initial("device-offline", "device/switch.garage", "t1", T1);

        Map<String, Object> result = contributor.contribute(config, ctx);

        assertThat(result.get("deviceId")).isEqualTo("switch.garage");
        assertThat(result.get("deviceClass")).isEqualTo("switch");
        assertThat(result).doesNotContainKey("roomType");
        assertThat(result.get("eventTimestamp")).isEqualTo(T1);
    }

    @Test
    void returnsSituationIdForNonDeviceCorrelationKey() {
        var config = new CaseTriggerConfig("io.casehub.iot", "generic-response", "1.0", Map.of());
        var ctx    = SituationContext.initial("sit-1", "user/admin", "t1", T1);

        Map<String, Object> result = contributor.contribute(config, ctx);

        assertThat(result).containsEntry("situationId", "sit-1");
        assertThat(result).doesNotContainKey("deviceId");
    }

    @Test
    void returnsSituationIdWhenDeviceNotInRegistry() {
        var config = new CaseTriggerConfig("io.casehub.iot", "generic-response", "1.0", Map.of());
        var ctx    = SituationContext.initial("sit-1", "device/unknown-device", "t1", T1);

        Map<String, Object> result = contributor.contribute(config, ctx);

        assertThat(result).containsEntry("situationId", "sit-1");
        assertThat(result).doesNotContainKey("deviceId");
    }

    @Test
    void usesLastSignalAsEventTimestamp() {
        Instant firstSignal = Instant.parse("2026-07-13T14:00:00Z");
        Instant lastSignal = Instant.parse("2026-07-13T14:30:00Z");

        DeviceEntity device = SwitchDevice.builder()
                .deviceId("switch.hall")
                .deviceClass(DeviceClass.SWITCH)
                .label("Hall Switch")
                .available(true)
                .lastUpdated(T1)
                .tenancyId("t1")
                .providerId("homeassistant")
                .on(true)
                .build();
        registry.addDevice(device);

        var config = new CaseTriggerConfig("io.casehub.iot", "generic-response", "1.0", Map.of());
        var ctx = SituationContext.initial("sit-1", "device/switch.hall", "t1", firstSignal);
        // Simulate a second detection to advance lastSignal
        var detection = new io.casehub.ras.api.DetectionResult(
                "g1", 0.9, io.casehub.ras.api.DetectionSignal.DETECTED, Map.of());
        ctx = ctx.withDetection(detection, lastSignal);

        Map<String, Object> result = contributor.contribute(config, ctx);

        assertThat(result.get("eventTimestamp")).isEqualTo(lastSignal);
    }
}
