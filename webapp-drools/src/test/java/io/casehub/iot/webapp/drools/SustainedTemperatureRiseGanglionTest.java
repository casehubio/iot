package io.casehub.iot.webapp.drools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.ras.api.DetectionResult;
import io.casehub.ras.api.DetectionSignal;
import io.casehub.ras.api.SituationContext;
import io.casehub.ras.drools.DroolsSessionKey;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class SustainedTemperatureRiseGanglionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private TestDroolsSessionStore sessionStore;
    private SustainedTemperatureRiseGanglion ganglion;

    @BeforeEach
    void setUp() {
        sessionStore = new TestDroolsSessionStore();
        // Short window and count for testing: 5 readings over 5 minutes, 2.0C delta
        ganglion = new SustainedTemperatureRiseGanglion(
                sessionStore, 5L, 5, new BigDecimal("2.0"));
    }

    private CloudEvent sensorEvent(String deviceId, BigDecimal tempC, Instant time) {
        ObjectNode after = MAPPER.createObjectNode();
        after.put("deviceId", deviceId);
        after.put("sensorType", "TEMPERATURE");
        after.put("numericValue", tempC);
        after.put("unit", "CELSIUS");

        ObjectNode data = MAPPER.createObjectNode();
        data.set("after", after);

        return CloudEventBuilder.v1()
                .withId("evt-" + time.toEpochMilli())
                .withSource(URI.create("/device/" + deviceId))
                .withType("io.casehub.iot.state_change.sensor")
                .withTime(OffsetDateTime.ofInstant(time, ZoneOffset.UTC))
                .withData("application/json", data.toString().getBytes())
                .build();
    }

    private CloudEvent thermostatEvent(String deviceId, BigDecimal tempC, Instant time) {
        ObjectNode currentTemp = MAPPER.createObjectNode();
        currentTemp.put("value", tempC);
        currentTemp.put("unit", "CELSIUS");

        ObjectNode after = MAPPER.createObjectNode();
        after.put("deviceId", deviceId);
        after.set("currentTemperature", currentTemp);

        ObjectNode data = MAPPER.createObjectNode();
        data.set("after", after);

        return CloudEventBuilder.v1()
                .withId("evt-" + time.toEpochMilli())
                .withSource(URI.create("/device/" + deviceId))
                .withType("io.casehub.iot.state_change.thermostat")
                .withTime(OffsetDateTime.ofInstant(time, ZoneOffset.UTC))
                .withData("application/json", data.toString().getBytes())
                .build();
    }

    private SituationContext testContext() {
        return SituationContext.initial("fire-risk", "key-1", "tenant-a",
                Instant.parse("2026-07-01T10:00:00Z"));
    }

    @Test
    void ganglionIdAndHandledEventTypes() {
        assertThat(ganglion.ganglionId()).isEqualTo("sustained-rise");
        assertThat(ganglion.handledEventTypes())
                .containsExactlyInAnyOrder(
                        "io.casehub.iot.state_change.sensor",
                        "io.casehub.iot.state_change.thermostat");
    }

    @Test
    void singleReadingReturnsNoise() {
        var event = sensorEvent("temp-1", new BigDecimal("20.0"),
                Instant.parse("2026-07-01T10:00:00Z"));
        DetectionResult result = ganglion.detect(event, testContext());
        assertThat(result.signal()).isEqualTo(DetectionSignal.NOISE);
    }

    @Test
    void fiveMonotonicIncreasingReadingsDetected() {
        var ctx = testContext();
        Instant base = Instant.parse("2026-07-01T10:00:00Z");

        // Rising: 20, 22.5, 25, 27.5, 30 (delta >= 2C each)
        ganglion.detect(sensorEvent("temp-1", new BigDecimal("20.0"), base), ctx)
                ;
        ganglion.detect(sensorEvent("temp-1", new BigDecimal("22.5"), base.plusSeconds(60)), ctx)
                ;
        ganglion.detect(sensorEvent("temp-1", new BigDecimal("25.0"), base.plusSeconds(120)), ctx)
                ;
        ganglion.detect(sensorEvent("temp-1", new BigDecimal("27.5"), base.plusSeconds(180)), ctx)
                ;

        // Fifth reading triggers detection
        DetectionResult result = ganglion.detect(
                sensorEvent("temp-1", new BigDecimal("30.0"), base.plusSeconds(240)), ctx)
                ;

        assertThat(result.signal()).isEqualTo(DetectionSignal.DETECTED);
        assertThat(result.confidence()).isGreaterThanOrEqualTo(0.8);
        assertThat(result.ganglionId()).isEqualTo("sustained-rise");
    }

    @Test
    void nonMonotonicSequenceReturnsNoise() {
        var ctx = testContext();
        Instant base = Instant.parse("2026-07-01T10:00:00Z");

        // Not monotonic: 20, 22, 21, 23, 25 — third reading drops
        ganglion.detect(sensorEvent("temp-1", new BigDecimal("20.0"), base), ctx)
                ;
        ganglion.detect(sensorEvent("temp-1", new BigDecimal("22.0"), base.plusSeconds(60)), ctx)
                ;
        ganglion.detect(sensorEvent("temp-1", new BigDecimal("21.0"), base.plusSeconds(120)), ctx)
                ;
        ganglion.detect(sensorEvent("temp-1", new BigDecimal("23.0"), base.plusSeconds(180)), ctx)
                ;

        DetectionResult result = ganglion.detect(
                sensorEvent("temp-1", new BigDecimal("25.0"), base.plusSeconds(240)), ctx)
                ;

        assertThat(result.signal()).isEqualTo(DetectionSignal.NOISE);
    }

    @Test
    void deltaBelowThresholdReturnsNoise() {
        var ctx = testContext();
        Instant base = Instant.parse("2026-07-01T10:00:00Z");

        // Rising but deltas < 2C: 20, 21.5, 23, 24.5, 26
        ganglion.detect(sensorEvent("temp-1", new BigDecimal("20.0"), base), ctx)
                ;
        ganglion.detect(sensorEvent("temp-1", new BigDecimal("21.5"), base.plusSeconds(60)), ctx)
                ;
        ganglion.detect(sensorEvent("temp-1", new BigDecimal("23.0"), base.plusSeconds(120)), ctx)
                ;
        ganglion.detect(sensorEvent("temp-1", new BigDecimal("24.5"), base.plusSeconds(180)), ctx)
                ;

        DetectionResult result = ganglion.detect(
                sensorEvent("temp-1", new BigDecimal("26.0"), base.plusSeconds(240)), ctx)
                ;

        assertThat(result.signal()).isEqualTo(DetectionSignal.NOISE);
    }

    @Test
    void thermostatEventsAlsoDetected() {
        var ctx = testContext();
        Instant base = Instant.parse("2026-07-01T10:00:00Z");

        // Mix sensor and thermostat events
        ganglion.detect(thermostatEvent("therm-1", new BigDecimal("20.0"), base), ctx)
                ;
        ganglion.detect(sensorEvent("temp-1", new BigDecimal("22.5"), base.plusSeconds(60)), ctx)
                ;
        ganglion.detect(thermostatEvent("therm-1", new BigDecimal("25.0"), base.plusSeconds(120)), ctx)
                ;
        ganglion.detect(sensorEvent("temp-1", new BigDecimal("27.5"), base.plusSeconds(180)), ctx)
                ;

        DetectionResult result = ganglion.detect(
                thermostatEvent("therm-1", new BigDecimal("30.0"), base.plusSeconds(240)), ctx)
                ;

        assertThat(result.signal()).isEqualTo(DetectionSignal.DETECTED);
    }

    @Test
    void sessionStatePreservedAcrossDetections() {
        var ctx = testContext();
        Instant base = Instant.parse("2026-07-01T10:00:00Z");

        ganglion.detect(sensorEvent("temp-1", new BigDecimal("20.0"), base), ctx)
                ;
        ganglion.detect(sensorEvent("temp-1", new BigDecimal("22.5"), base.plusSeconds(60)), ctx)
                ;

        // Session should exist in store
        assertThat(sessionStore.get("sustained-rise", "fire-risk", "key-1", "tenant-a"))
                .isPresent();

        ganglion.detect(sensorEvent("temp-1", new BigDecimal("25.0"), base.plusSeconds(120)), ctx)
                ;
        ganglion.detect(sensorEvent("temp-1", new BigDecimal("27.5"), base.plusSeconds(180)), ctx)
                ;

        DetectionResult result = ganglion.detect(
                sensorEvent("temp-1", new BigDecimal("30.0"), base.plusSeconds(240)), ctx)
                ;

        assertThat(result.signal()).isEqualTo(DetectionSignal.DETECTED);
    }

    @Test
    void closeRemovesSession() {
        var ctx = testContext();
        ganglion.detect(sensorEvent("temp-1", new BigDecimal("20.0"),
                Instant.parse("2026-07-01T10:00:00Z")), ctx);

        assertThat(sessionStore.get("sustained-rise", "fire-risk", "key-1", "tenant-a"))
                .isPresent();

        ganglion.close("fire-risk", "key-1", "tenant-a");

        assertThat(sessionStore.get("sustained-rise", "fire-risk", "key-1", "tenant-a"))
                .isEmpty();
    }

    @Test
    void outOfOrderEventsThrow() {
        var ctx = testContext();

        ganglion.detect(sensorEvent("temp-1", new BigDecimal("20.0"),
                Instant.parse("2026-07-01T10:01:00Z")), ctx);

        var outOfOrderEvent = sensorEvent("temp-1", new BigDecimal("22.0"),
                Instant.parse("2026-07-01T10:00:00Z"));

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> ganglion.detect(outOfOrderEvent, ctx));
    }
}
