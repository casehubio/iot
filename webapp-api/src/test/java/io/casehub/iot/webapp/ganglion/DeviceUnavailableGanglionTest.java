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

class DeviceUnavailableGanglionTest {

    private final DeviceUnavailableGanglion ganglion = new DeviceUnavailableGanglion();

    private static final SituationContext CTX = SituationContext.initial(
            "device-offline", "home-1", "default-tenant", Instant.now());

    @Test
    void detectsDeviceBecomingUnavailable() {
        var event = buildCloudEvent("io.casehub.iot.state_change.sensor",
                """
                {"after":{"deviceClass":"SENSOR","available":false}}
                """);

        var result = ganglion.evaluate(event, CTX);

        assertThat(result.signal()).isEqualTo(DetectionSignal.DETECTED);
        assertThat(result.confidence()).isGreaterThan(0.7);
        assertThat(result.ganglionId()).isEqualTo("device-unavailable");
    }

    @Test
    void returnsNoiseForAvailableDevice() {
        var event = buildCloudEvent("io.casehub.iot.state_change.lock",
                """
                {"after":{"deviceClass":"LOCK","available":true,"isLocked":true}}
                """);

        var result = ganglion.evaluate(event, CTX);

        assertThat(result.signal()).isEqualTo(DetectionSignal.NOISE);
    }

    @Test
    void returnsNoiseWhenAvailableFieldMissing() {
        var event = buildCloudEvent("io.casehub.iot.state_change.sensor",
                """
                {"after":{"deviceClass":"SENSOR","numericValue":22.0}}
                """);

        var result = ganglion.evaluate(event, CTX);

        assertThat(result.signal()).isEqualTo(DetectionSignal.NOISE);
    }

    @Test
    void handlesMultipleDeviceTypes() {
        var sensorEvent = buildCloudEvent("io.casehub.iot.state_change.sensor",
                """
                {"after":{"deviceClass":"SENSOR","available":false}}
                """);

        var lockEvent = buildCloudEvent("io.casehub.iot.state_change.lock",
                """
                {"after":{"deviceClass":"LOCK","available":false}}
                """);

        assertThat(ganglion.evaluate(sensorEvent, CTX).signal()).isEqualTo(DetectionSignal.DETECTED);
        assertThat(ganglion.evaluate(lockEvent, CTX).signal()).isEqualTo(DetectionSignal.DETECTED);
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
