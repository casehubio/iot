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

class PowerAnomalyGanglionTest {

    private final PowerAnomalyGanglion ganglion = new PowerAnomalyGanglion(new BigDecimal("5000"));

    private static final SituationContext CTX = SituationContext.initial(
            "power-spike", "home-1", "default-tenant", Instant.now());

    @Test
    void detectsPowerAboveThreshold() {
        var event = buildCloudEvent(
                """
                {"after":{"deviceClass":"POWER_SENSOR","power":{"value":6500,"unit":"WATTS"}}}
                """);

        var result = ganglion.evaluate(event, CTX);

        assertThat(result.signal()).isEqualTo(DetectionSignal.DETECTED);
        assertThat(result.confidence()).isGreaterThan(0.6);
        assertThat(result.ganglionId()).isEqualTo("power-anomaly");
    }

    @Test
    void returnsNoiseForNormalPower() {
        var event = buildCloudEvent(
                """
                {"after":{"deviceClass":"POWER_SENSOR","power":{"value":1200,"unit":"WATTS"}}}
                """);

        var result = ganglion.evaluate(event, CTX);

        assertThat(result.signal()).isEqualTo(DetectionSignal.NOISE);
    }

    @Test
    void returnsNoiseForNullPower() {
        var event = buildCloudEvent(
                """
                {"after":{"deviceClass":"POWER_SENSOR","available":true}}
                """);

        var result = ganglion.evaluate(event, CTX);

        assertThat(result.signal()).isEqualTo(DetectionSignal.NOISE);
    }

    @Test
    void handledEventTypesIncludesPowerSensor() {
        assertThat(ganglion.handledEventTypes()).containsExactly(
                "io.casehub.iot.state_change.power_sensor");
    }

    private static CloudEvent buildCloudEvent(String data) {
        return CloudEventBuilder.v1()
                .withId("test-" + System.nanoTime())
                .withSource(URI.create("/casehub-iot"))
                .withType("io.casehub.iot.state_change.power_sensor")
                .withTime(OffsetDateTime.now())
                .withData("application/json", data.getBytes())
                .withExtension("tenancyid", "default-tenant")
                .build();
    }
}
