package io.casehub.iot.webapp.ganglion;

import io.casehub.ras.api.DetectionSignal;
import io.casehub.ras.api.SituationContext;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class TemperatureThresholdGanglionTest {

    private final TemperatureThresholdGanglion ganglion =
            new TemperatureThresholdGanglion(new BigDecimal("35.0"), new BigDecimal("5.0"));

    private static final SituationContext CTX = SituationContext.initial(
            "fire-risk", "home-1", "default-tenant", Instant.now());

    @Test
    void detectsTemperatureAboveUpperThreshold() {
        var event = buildCloudEvent("io.casehub.iot.state_change.sensor",
                """
                {"after":{"deviceClass":"SENSOR","sensorType":"TEMPERATURE","numericValue":38.5,"unit":"CELSIUS"}}
                """);

        var result = ganglion.evaluate(event, CTX);

        assertThat(result.signal()).isEqualTo(DetectionSignal.DETECTED);
        assertThat(result.confidence()).isGreaterThan(0.5);
        assertThat(result.ganglionId()).isEqualTo("temperature-threshold");
    }

    @Test
    void returnsNoiseForNormalTemperature() {
        var event = buildCloudEvent("io.casehub.iot.state_change.sensor",
                """
                {"after":{"deviceClass":"SENSOR","sensorType":"TEMPERATURE","numericValue":22.0,"unit":"CELSIUS"}}
                """);

        var result = ganglion.evaluate(event, CTX);

        assertThat(result.signal()).isEqualTo(DetectionSignal.NOISE);
    }

    @Test
    void detectsTemperatureBelowLowerThreshold() {
        var event = buildCloudEvent("io.casehub.iot.state_change.sensor",
                """
                {"after":{"deviceClass":"SENSOR","sensorType":"TEMPERATURE","numericValue":3.0,"unit":"CELSIUS"}}
                """);

        var result = ganglion.evaluate(event, CTX);

        assertThat(result.signal()).isEqualTo(DetectionSignal.DETECTED);
    }

    @Test
    void handlesNonTemperatureSensorAsNoise() {
        var event = buildCloudEvent("io.casehub.iot.state_change.sensor",
                """
                {"after":{"deviceClass":"SENSOR","sensorType":"HUMIDITY","numericValue":85.0,"unit":"%"}}
                """);

        var result = ganglion.evaluate(event, CTX);

        assertThat(result.signal()).isEqualTo(DetectionSignal.NOISE);
    }

    @Test
    void handledEventTypesIncludesSensorAndThermostat() {
        assertThat(ganglion.handledEventTypes()).containsExactlyInAnyOrder(
                "io.casehub.iot.state_change.sensor",
                "io.casehub.iot.state_change.thermostat");
    }

    private static CloudEvent buildCloudEvent(String type, String data) {
        return CloudEventBuilder.v1()
                .withId("test-" + System.nanoTime())
                .withSource(URI.create("/casehub-iot"))
                .withType(type)
                .withTime(OffsetDateTime.now())
                .withData("application/json", data.getBytes())
                .withExtension("tenancyid", "default-tenant")
                .build();
    }
}
