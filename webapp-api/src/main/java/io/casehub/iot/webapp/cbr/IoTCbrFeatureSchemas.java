package io.casehub.iot.webapp.cbr;

import io.casehub.neocortex.memory.cbr.CbrFeatureSchema;
import io.casehub.neocortex.memory.cbr.FeatureField;
import io.casehub.neocortex.memory.cbr.SimilaritySpec;

import java.util.ArrayList;
import java.util.List;

public final class IoTCbrFeatureSchemas {

    private IoTCbrFeatureSchemas() {}

    public static CbrFeatureSchema hvacAnomaly() {
        var fields = new ArrayList<>(commonFields());
        fields.add(FeatureField.numeric("temperatureDelta", -20, 20,
                new SimilaritySpec.GaussianDecay(2.0)));
        fields.add(FeatureField.categorical("outdoorTemperatureRange"));
        return new CbrFeatureSchema("hvac-anomaly", fields);
    }

    public static CbrFeatureSchema safetyAlert() {
        var fields = new ArrayList<>(commonFields());
        fields.add(FeatureField.categorical("alertType"));
        return new CbrFeatureSchema("safety-alert", fields);
    }

    public static CbrFeatureSchema securityAlert() {
        var fields = new ArrayList<>(commonFields());
        fields.add(FeatureField.categorical("entryPoint"));
        return new CbrFeatureSchema("security-alert", fields);
    }

    public static CbrFeatureSchema genericResponse() {
        return new CbrFeatureSchema("generic-response", commonFields());
    }

    private static List<FeatureField> commonFields() {
        return List.of(
                FeatureField.categorical("deviceClass", deviceClassSimilarity()),
                FeatureField.categorical("roomType", roomTypeSimilarity()),
                FeatureField.numeric("hourOfDay", 0, 23,
                        new SimilaritySpec.GaussianDecay(3.0)),
                FeatureField.categorical("dayType"),
                FeatureField.categorical("season", seasonSimilarity())
        );
    }

    private static SimilaritySpec deviceClassSimilarity() {
        return SimilaritySpec.categoricalTableBuilder()
                .add("thermostat", "hvac", 0.6)
                .add("thermostat", "temperature_sensor", 0.4)
                .add("hvac", "temperature_sensor", 0.3)
                .add("motion_sensor", "occupancy_sensor", 0.7)
                .add("door_lock", "window_sensor", 0.3)
                .add("smoke_detector", "co_detector", 0.6)
                .add("smoke_detector", "gas_detector", 0.5)
                .add("co_detector", "gas_detector", 0.6)
                .add("camera", "motion_sensor", 0.3)
                .add("light", "dimmer", 0.8)
                .build();
    }

    private static SimilaritySpec roomTypeSimilarity() {
        return SimilaritySpec.categoricalTableBuilder()
                .add("bedroom", "office", 0.3)
                .add("bedroom", "nursery", 0.7)
                .add("kitchen", "dining", 0.7)
                .add("kitchen", "utility", 0.4)
                .add("living_room", "dining", 0.5)
                .add("living_room", "family_room", 0.8)
                .add("bathroom", "utility", 0.3)
                .add("garage", "workshop", 0.6)
                .add("hallway", "stairway", 0.7)
                .add("patio", "garden", 0.6)
                .build();
    }

    private static SimilaritySpec seasonSimilarity() {
        return SimilaritySpec.categoricalTableBuilder()
                .add("spring", "summer", 0.5)
                .add("spring", "autumn", 0.5)
                .add("spring", "winter", 0.2)
                .add("summer", "autumn", 0.2)
                .add("summer", "winter", 0.1)
                .add("autumn", "winter", 0.5)
                .build();
    }
}
