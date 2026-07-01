package io.casehub.iot.webapp.ganglion;

import io.casehub.ras.api.DetectionSignal;
import io.casehub.ras.api.SituationContext;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class MotionAtTimeGanglionTest {

    private final MotionAtTimeGanglion ganglion =
            new MotionAtTimeGanglion(LocalTime.of(23, 0), LocalTime.of(6, 0));

    private static final SituationContext CTX = SituationContext.initial(
            "intrusion", "home-1", "default-tenant", Instant.now());

    @Test
    void detectsMotionDuringRestrictedHoursAtNight() {
        var event = buildCloudEvent(
                """
                {"after":{"deviceClass":"PRESENCE_SENSOR","isPresent":true}}
                """,
                LocalTime.of(2, 30)); // 02:30 is within 23:00-06:00

        var result = ganglion.evaluate(event, CTX);

        assertThat(result.signal()).isEqualTo(DetectionSignal.DETECTED);
        assertThat(result.confidence()).isGreaterThan(0.5);
        assertThat(result.ganglionId()).isEqualTo("motion-at-time");
    }

    @Test
    void returnsNoiseForMotionDuringDayHours() {
        var event = buildCloudEvent(
                """
                {"after":{"deviceClass":"PRESENCE_SENSOR","isPresent":true}}
                """,
                LocalTime.of(14, 0)); // 14:00 is outside 23:00-06:00

        var result = ganglion.evaluate(event, CTX);

        assertThat(result.signal()).isEqualTo(DetectionSignal.NOISE);
    }

    @Test
    void returnsNoiseForNoMotionDuringRestrictedHours() {
        var event = buildCloudEvent(
                """
                {"after":{"deviceClass":"PRESENCE_SENSOR","isPresent":false}}
                """,
                LocalTime.of(3, 0));

        var result = ganglion.evaluate(event, CTX);

        assertThat(result.signal()).isEqualTo(DetectionSignal.NOISE);
    }

    @Test
    void handledEventTypesIncludesPresenceSensor() {
        assertThat(ganglion.handledEventTypes()).containsExactly(
                "io.casehub.iot.state_change.presence_sensor");
    }

    private static CloudEvent buildCloudEvent(String data, LocalTime eventTime) {
        var now = OffsetDateTime.now();
        var withTime = now.with(eventTime);

        return CloudEventBuilder.v1()
                .withId("test-" + System.nanoTime())
                .withSource(URI.create("/casehub-iot"))
                .withType("io.casehub.iot.state_change.presence_sensor")
                .withTime(withTime)
                .withData("application/json", data.getBytes())
                .withExtension("tenancyid", "default-tenant")
                .build();
    }
}
