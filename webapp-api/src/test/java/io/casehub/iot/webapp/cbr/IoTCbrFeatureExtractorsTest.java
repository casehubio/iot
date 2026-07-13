package io.casehub.iot.webapp.cbr;

import io.casehub.api.context.CaseContext;
import io.casehub.api.context.ReadableLayer;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IoTCbrFeatureExtractorsTest {

    @Test
    void extractHvacFeatures_returnsExpectedKeys() {
        var ctx = mockContext(Map.of(
                "deviceClass", "thermostat",
                "roomType", "bedroom",
                "eventTimestamp", "2026-01-15T03:30:00Z",
                "temperatureDelta", 4.5,
                "outdoorTemperatureRange", "cold"
        ));

        var features = IoTCbrFeatureExtractors.extractHvacAnomalyFeatures(ctx);

        assertThat(features).containsKeys(
                "deviceClass", "roomType", "hourOfDay", "dayType", "season",
                "temperatureDelta", "outdoorTemperatureRange");
        assertThat(features.get("deviceClass")).isEqualTo("thermostat");
        assertThat(features.get("roomType")).isEqualTo("bedroom");
        assertThat(features.get("hourOfDay")).isEqualTo(3.0);
        assertThat(features.get("dayType")).isEqualTo("weekday");
        assertThat(features.get("season")).isEqualTo("winter");
        assertThat(features.get("temperatureDelta")).isEqualTo(4.5);
        assertThat(features.get("outdoorTemperatureRange")).isEqualTo("cold");
    }

    @Test
    void extractHvacFeatures_summerWeekend() {
        var ctx = mockContext(Map.of(
                "deviceClass", "hvac",
                "roomType", "living_room",
                "eventTimestamp", "2026-07-11T15:00:00Z",
                "temperatureDelta", -2.0,
                "outdoorTemperatureRange", "hot"
        ));

        var features = IoTCbrFeatureExtractors.extractHvacAnomalyFeatures(ctx);

        assertThat(features.get("hourOfDay")).isEqualTo(15.0);
        assertThat(features.get("dayType")).isEqualTo("weekend");
        assertThat(features.get("season")).isEqualTo("summer");
    }

    @Test
    void extractHvacFeatures_emptyContext_returnsEmptyMap() {
        var ctx = mockContext(Map.of());
        var features = IoTCbrFeatureExtractors.extractHvacAnomalyFeatures(ctx);
        assertThat(features).isEmpty();
    }

    @Test
    void extractHvacFeatures_missingOptionalFields_stillReturnsCommon() {
        var ctx = mockContext(Map.of(
                "deviceClass", "thermostat",
                "eventTimestamp", "2026-03-20T12:00:00Z"
        ));

        var features = IoTCbrFeatureExtractors.extractHvacAnomalyFeatures(ctx);
        assertThat(features).containsKey("deviceClass");
        assertThat(features).containsKeys("hourOfDay", "dayType", "season");
        assertThat(features).doesNotContainKey("temperatureDelta");
        assertThat(features).doesNotContainKey("roomType");
    }

    @Test
    void extractSafetyFeatures_includesAlertType() {
        var ctx = mockContext(Map.of(
                "deviceClass", "smoke_detector",
                "roomType", "kitchen",
                "eventTimestamp", "2026-06-01T10:00:00Z",
                "alertType", "smoke"
        ));

        var features = IoTCbrFeatureExtractors.extractSafetyAlertFeatures(ctx);
        assertThat(features.get("alertType")).isEqualTo("smoke");
        assertThat(features).containsKeys("deviceClass", "roomType", "hourOfDay");
    }

    @Test
    void extractSecurityFeatures_includesEntryPoint() {
        var ctx = mockContext(Map.of(
                "deviceClass", "door_lock",
                "roomType", "hallway",
                "eventTimestamp", "2026-12-24T02:00:00Z",
                "entryPoint", "front_door"
        ));

        var features = IoTCbrFeatureExtractors.extractSecurityAlertFeatures(ctx);
        assertThat(features.get("entryPoint")).isEqualTo("front_door");
        assertThat(features.get("season")).isEqualTo("winter");
    }

    @Test
    void extractGenericFeatures_commonOnly() {
        var ctx = mockContext(Map.of(
                "deviceClass", "light",
                "roomType", "living_room",
                "eventTimestamp", "2026-09-15T18:30:00Z"
        ));

        var features = IoTCbrFeatureExtractors.extractGenericResponseFeatures(ctx);
        assertThat(features).containsKeys("deviceClass", "roomType", "hourOfDay", "dayType", "season");
        assertThat(features.get("season")).isEqualTo("autumn");
    }

    @Test
    void seasonDerivation_marchIsSpring() {
        var ctx = mockContext(Map.of("eventTimestamp", "2026-03-21T12:00:00Z"));
        var features = IoTCbrFeatureExtractors.extractGenericResponseFeatures(ctx);
        assertThat(features.get("season")).isEqualTo("spring");
    }

    @Test
    void seasonDerivation_decemberIsWinter() {
        var ctx = mockContext(Map.of("eventTimestamp", "2026-12-01T12:00:00Z"));
        var features = IoTCbrFeatureExtractors.extractGenericResponseFeatures(ctx);
        assertThat(features.get("season")).isEqualTo("winter");
    }

    private static CaseContext mockContext(Map<String, Object> data) {
        var ctx = mock(CaseContext.class);
        var layer = mock(ReadableLayer.class);
        when(ctx.layer("working")).thenReturn(layer);
        for (var entry : data.entrySet()) {
            when(layer.get(entry.getKey())).thenReturn(entry.getValue());
        }
        return ctx;
    }
}
