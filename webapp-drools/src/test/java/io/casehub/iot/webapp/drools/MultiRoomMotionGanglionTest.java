package io.casehub.iot.webapp.drools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.ras.api.DetectionResult;
import io.casehub.ras.api.DetectionSignal;
import io.casehub.ras.api.SituationContext;
import io.casehub.ras.drools.InMemoryDroolsSessionStore;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class MultiRoomMotionGanglionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private InMemoryDroolsSessionStore sessionStore;
    private MultiRoomMotionGanglion ganglion;

    @BeforeEach
    void setUp() {
        sessionStore = new InMemoryDroolsSessionStore();
        // Short window for testing: 2 minutes, 3 distinct devices
        ganglion = new MultiRoomMotionGanglion(sessionStore, 2L, 3);
    }

    private CloudEvent motionEvent(String deviceId, boolean motion, Instant time) {
        ObjectNode after = MAPPER.createObjectNode();
        after.put("deviceId", deviceId);
        after.put("motion", motion);

        ObjectNode data = MAPPER.createObjectNode();
        data.set("after", after);

        return CloudEventBuilder.v1()
                .withId("evt-" + deviceId + "-" + time.toEpochMilli())
                .withSource(URI.create("/device/" + deviceId))
                .withType("io.casehub.iot.state_change.presence_sensor")
                .withTime(OffsetDateTime.ofInstant(time, ZoneOffset.UTC))
                .withData("application/json", data.toString().getBytes())
                .build();
    }

    private SituationContext testContext() {
        return SituationContext.initial("intrusion", "key-1", "tenant-a",
                Instant.parse("2026-07-01T10:00:00Z"));
    }

    @Test
    void ganglionIdAndHandledEventTypes() {
        assertThat(ganglion.ganglionId()).isEqualTo("multi-room-motion");
        assertThat(ganglion.handledEventTypes())
                .containsExactly("io.casehub.iot.state_change.presence_sensor");
    }

    @Test
    void singleMotionEventReturnsNoise() {
        var event = motionEvent("motion-1", true, Instant.parse("2026-07-01T10:00:00Z"));
        DetectionResult result = ganglion.detect(event, testContext()).await().indefinitely();
        assertThat(result.signal()).isEqualTo(DetectionSignal.NOISE);
    }

    @Test
    void twoDistinctDevicesReturnsNoise() {
        Instant base = Instant.parse("2026-07-01T10:00:00Z");
        var ctx = testContext();

        ganglion.detect(motionEvent("motion-1", true, base), ctx).await().indefinitely();
        DetectionResult result = ganglion.detect(
                motionEvent("motion-2", true, base.plusSeconds(30)), ctx)
                .await().indefinitely();

        assertThat(result.signal()).isEqualTo(DetectionSignal.NOISE);
    }

    @Test
    void threeDistinctDevicesDetected() {
        Instant base = Instant.parse("2026-07-01T10:00:00Z");
        var ctx = testContext();

        ganglion.detect(motionEvent("motion-1", true, base), ctx).await().indefinitely();
        ganglion.detect(motionEvent("motion-2", true, base.plusSeconds(30)), ctx)
                .await().indefinitely();

        DetectionResult result = ganglion.detect(
                motionEvent("motion-3", true, base.plusSeconds(60)), ctx)
                .await().indefinitely();

        assertThat(result.signal()).isEqualTo(DetectionSignal.DETECTED);
        assertThat(result.confidence()).isGreaterThanOrEqualTo(0.7);
        assertThat(result.ganglionId()).isEqualTo("multi-room-motion");
        assertThat(result.evidence()).containsKey("distinctDevices");
    }

    @Test
    void duplicateDeviceIdDoesNotCount() {
        Instant base = Instant.parse("2026-07-01T10:00:00Z");
        var ctx = testContext();

        ganglion.detect(motionEvent("motion-1", true, base), ctx).await().indefinitely();
        ganglion.detect(motionEvent("motion-2", true, base.plusSeconds(30)), ctx)
                .await().indefinitely();
        // Same device (motion-1) again — doesn't add to distinct count
        ganglion.detect(motionEvent("motion-1", true, base.plusSeconds(60)), ctx)
                .await().indefinitely();

        DetectionResult result = ganglion.detect(
                motionEvent("motion-1", true, base.plusSeconds(90)), ctx)
                .await().indefinitely();

        assertThat(result.signal()).isEqualTo(DetectionSignal.NOISE);
    }

    @Test
    void motionFalseEventsIgnored() {
        Instant base = Instant.parse("2026-07-01T10:00:00Z");
        var ctx = testContext();

        // Only motion=true events count
        ganglion.detect(motionEvent("motion-1", true, base), ctx).await().indefinitely();
        ganglion.detect(motionEvent("motion-2", false, base.plusSeconds(30)), ctx)
                .await().indefinitely();
        ganglion.detect(motionEvent("motion-3", true, base.plusSeconds(60)), ctx)
                .await().indefinitely();

        DetectionResult result = ganglion.detect(
                motionEvent("motion-4", false, base.plusSeconds(90)), ctx)
                .await().indefinitely();

        // Only motion-1 and motion-3 counted (2 distinct)
        assertThat(result.signal()).isEqualTo(DetectionSignal.NOISE);
    }

    @Test
    void fourDistinctDevicesAlsoDetected() {
        Instant base = Instant.parse("2026-07-01T10:00:00Z");
        var ctx = testContext();

        ganglion.detect(motionEvent("motion-1", true, base), ctx).await().indefinitely();
        ganglion.detect(motionEvent("motion-2", true, base.plusSeconds(20)), ctx)
                .await().indefinitely();
        ganglion.detect(motionEvent("motion-3", true, base.plusSeconds(40)), ctx)
                .await().indefinitely();

        DetectionResult result = ganglion.detect(
                motionEvent("motion-4", true, base.plusSeconds(60)), ctx)
                .await().indefinitely();

        assertThat(result.signal()).isEqualTo(DetectionSignal.DETECTED);
        assertThat(result.evidence().get("distinctDevices")).isEqualTo(4);
    }

    @Test
    void eventsOutsideWindowExpire() {
        Instant base = Instant.parse("2026-07-01T10:00:00Z");
        var ctx = testContext();

        // First two events
        ganglion.detect(motionEvent("motion-1", true, base), ctx).await().indefinitely();
        ganglion.detect(motionEvent("motion-2", true, base.plusSeconds(30)), ctx)
                .await().indefinitely();

        // Third event beyond 2-minute window from first
        DetectionResult result = ganglion.detect(
                motionEvent("motion-3", true, base.plusSeconds(121)), ctx)
                .await().indefinitely();

        // First event expired, only motion-2 and motion-3 in window (2 distinct)
        assertThat(result.signal()).isEqualTo(DetectionSignal.NOISE);
    }

    @Test
    void sessionPreservedAcrossDetections() {
        var ctx = testContext();
        Instant base = Instant.parse("2026-07-01T10:00:00Z");

        ganglion.detect(motionEvent("motion-1", true, base), ctx).await().indefinitely();

        // Session exists after first event
        assertThat(sessionStore.get("multi-room-motion", "intrusion", "key-1", "tenant-a"))
                .isPresent();

        ganglion.detect(motionEvent("motion-2", true, base.plusSeconds(30)), ctx)
                .await().indefinitely();

        // Still present
        assertThat(sessionStore.get("multi-room-motion", "intrusion", "key-1", "tenant-a"))
                .isPresent();
    }

    @Test
    void closeRemovesSession() {
        var ctx = testContext();
        ganglion.detect(motionEvent("motion-1", true, Instant.parse("2026-07-01T10:00:00Z")), ctx)
                .await().indefinitely();

        assertThat(sessionStore.get("multi-room-motion", "intrusion", "key-1", "tenant-a"))
                .isPresent();

        ganglion.close("intrusion", "key-1", "tenant-a").await().indefinitely();

        assertThat(sessionStore.get("multi-room-motion", "intrusion", "key-1", "tenant-a"))
                .isEmpty();
    }
}
