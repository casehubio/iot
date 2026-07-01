package io.casehub.iot.webapp.ganglion;

import io.casehub.ras.api.DetectionSignal;
import io.casehub.ras.api.SituationContext;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class LockStateGanglionTest {

    private final LockStateGanglion ganglion = new LockStateGanglion();

    private static final SituationContext CTX = SituationContext.initial(
            "security-breach", "home-1", "default-tenant", Instant.now());

    @Test
    void detectsUnexpectedUnlock() {
        var event = buildCloudEvent(
                """
                {"before":{"deviceClass":"LOCK","isLocked":true},"after":{"deviceClass":"LOCK","isLocked":false}}
                """);

        var result = ganglion.evaluate(event, CTX);

        assertThat(result.signal()).isEqualTo(DetectionSignal.DETECTED);
        assertThat(result.confidence()).isGreaterThan(0.6);
        assertThat(result.ganglionId()).isEqualTo("lock-state");
    }

    @Test
    void returnsNoiseForLocking() {
        var event = buildCloudEvent(
                """
                {"before":{"deviceClass":"LOCK","isLocked":false},"after":{"deviceClass":"LOCK","isLocked":true}}
                """);

        var result = ganglion.evaluate(event, CTX);

        assertThat(result.signal()).isEqualTo(DetectionSignal.NOISE);
    }

    @Test
    void returnsNoiseForAlreadyUnlocked() {
        var event = buildCloudEvent(
                """
                {"before":{"deviceClass":"LOCK","isLocked":false},"after":{"deviceClass":"LOCK","isLocked":false}}
                """);

        var result = ganglion.evaluate(event, CTX);

        assertThat(result.signal()).isEqualTo(DetectionSignal.NOISE);
    }

    @Test
    void returnsNoiseWhenBeforeMissing() {
        var event = buildCloudEvent(
                """
                {"after":{"deviceClass":"LOCK","isLocked":false}}
                """);

        var result = ganglion.evaluate(event, CTX);

        assertThat(result.signal()).isEqualTo(DetectionSignal.NOISE);
    }

    @Test
    void handledEventTypesIncludesLock() {
        assertThat(ganglion.handledEventTypes()).containsExactly(
                "io.casehub.iot.state_change.lock");
    }

    private static CloudEvent buildCloudEvent(String data) {
        return CloudEventBuilder.v1()
                .withId("test-" + System.nanoTime())
                .withSource(URI.create("/casehub-iot"))
                .withType("io.casehub.iot.state_change.lock")
                .withTime(OffsetDateTime.now())
                .withData("application/json", data.getBytes())
                .withExtension("tenancyid", "default-tenant")
                .build();
    }
}
