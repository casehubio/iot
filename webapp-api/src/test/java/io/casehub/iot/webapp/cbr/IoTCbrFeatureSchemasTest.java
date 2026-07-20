package io.casehub.iot.webapp.cbr;

import io.casehub.neocortex.memory.cbr.CbrFeatureSchema;
import io.casehub.neocortex.memory.cbr.FeatureField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class IoTCbrFeatureSchemasTest {

    private static final String[] COMMON_FEATURES =
            {"deviceClass", "roomType", "hourOfDay", "dayType", "season"};

    static Stream<CbrFeatureSchema> allSchemas() {
        return Stream.of(
                IoTCbrFeatureSchemas.hvacAnomaly(),
                IoTCbrFeatureSchemas.safetyAlert(),
                IoTCbrFeatureSchemas.securityAlert(),
                IoTCbrFeatureSchemas.genericResponse(),
                IoTCbrFeatureSchemas.workItemOutcome()
                        );}

    @ParameterizedTest
    @MethodSource("allSchemas")
    void allSchemas_haveCommonFeatures(CbrFeatureSchema schema) {
        assertThat(schema.fields())
                .extracting(FeatureField::name)
                .contains(COMMON_FEATURES);
    }

    @ParameterizedTest
    @MethodSource("allSchemas")
    void allSchemas_caseTypeNotBlank(CbrFeatureSchema schema) {
        assertThat(schema.caseType()).isNotBlank();
    }

    @Test
    void hvacAnomaly_hasTypeSpecificFields() {
        var schema = IoTCbrFeatureSchemas.hvacAnomaly();
        assertThat(schema.caseType()).isEqualTo("hvac-anomaly");
        assertThat(schema.fields())
                .extracting(FeatureField::name)
                .contains("temperatureDelta", "outdoorTemperatureRange");
    }

    @Test
    void safetyAlert_hasTypeSpecificFields() {
        var schema = IoTCbrFeatureSchemas.safetyAlert();
        assertThat(schema.caseType()).isEqualTo("safety-alert");
        assertThat(schema.fields())
                .extracting(FeatureField::name)
                .contains("alertType");
    }

    @Test
    void securityAlert_hasTypeSpecificFields() {
        var schema = IoTCbrFeatureSchemas.securityAlert();
        assertThat(schema.caseType()).isEqualTo("security-alert");
        assertThat(schema.fields())
                .extracting(FeatureField::name)
                .contains("entryPoint");
    }

    @Test
    void genericResponse_hasOnlyCommonFeatures() {
        var schema = IoTCbrFeatureSchemas.genericResponse();
        assertThat(schema.caseType()).isEqualTo("generic-response");
        assertThat(schema.fields())
                .extracting(FeatureField::name)
                .containsExactlyInAnyOrder(COMMON_FEATURES);
    }

    @Test
    void workItemOutcome_hasCorrectCaseTypeAndFields() {
        var schema = IoTCbrFeatureSchemas.workItemOutcome();
        assertThat(schema.caseType()).isEqualTo("iot-work-item");
        var fieldNames = schema.fields().stream()
                               .map(FeatureField::name).toList();
        assertThat(fieldNames).contains("deviceClass", "roomType", "hourOfDay",
                                        "dayType", "season", "caseType", "workerName", "priority",
                                        "candidateGroups");
        assertThat(fieldNames).doesNotContain("resolutionDurationMinutes",
                                              "resolvedBy", "terminalStatus");
    }


    @Test
    void hvacAnomaly_deviceClassIsCategoricalWithSimilarityTable() {
        var schema = IoTCbrFeatureSchemas.hvacAnomaly();
        var deviceClass = schema.fields().stream()
                .filter(f -> f.name().equals("deviceClass"))
                .findFirst().orElseThrow();
        assertThat(deviceClass).isInstanceOf(FeatureField.Categorical.class);
        assertThat(((FeatureField.Categorical) deviceClass).similaritySpec()).isNotNull();
    }

    @Test
    void hvacAnomaly_hourOfDayIsNumericWithGaussianDecay() {
        var schema = IoTCbrFeatureSchemas.hvacAnomaly();
        var hourOfDay = schema.fields().stream()
                .filter(f -> f.name().equals("hourOfDay"))
                .findFirst().orElseThrow();
        assertThat(hourOfDay).isInstanceOf(FeatureField.Numeric.class);
        var numeric = (FeatureField.Numeric) hourOfDay;
        assertThat(numeric.min()).isEqualTo(0);
        assertThat(numeric.max()).isEqualTo(23);
        assertThat(numeric.similaritySpec()).isNotNull();
    }

    @Test
    void hvacAnomaly_temperatureDeltaIsNumericWithGaussianDecay() {
        var schema = IoTCbrFeatureSchemas.hvacAnomaly();
        var tempDelta = schema.fields().stream()
                .filter(f -> f.name().equals("temperatureDelta"))
                .findFirst().orElseThrow();
        assertThat(tempDelta).isInstanceOf(FeatureField.Numeric.class);
        var numeric = (FeatureField.Numeric) tempDelta;
        assertThat(numeric.min()).isEqualTo(-20);
        assertThat(numeric.max()).isEqualTo(20);
    }

    @Test
    void situationDismissal_schemaHasCommonFieldsPlusConfidence() {
        var schema = IoTCbrFeatureSchemas.situationDismissal();
        assertThat(schema.caseType()).isEqualTo("iot-dismissal");
        var fieldNames = schema.fields().stream()
                               .map(io.casehub.neocortex.memory.cbr.FeatureField::name).toList();
        assertThat(fieldNames).contains("deviceClass", "roomType", "hourOfDay",
                                        "dayType", "season", "detectionConfidence");
        assertThat(fieldNames).hasSize(6);
    }
}
