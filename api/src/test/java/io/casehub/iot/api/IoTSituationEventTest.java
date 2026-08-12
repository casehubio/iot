package io.casehub.iot.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IoTSituationEventTest {

    private static final Instant NOW = Instant.parse("2026-08-12T10:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void triggeredTypeIncludesSituationId() {
        var event = new IoTSituationEvent(
                "temperature-threshold", "triggered", "sensor.outdoor",
                "tenant-1", Map.of("temperature", 42.0), NOW);
        assertThat(event.type()).isEqualTo("io.casehub.iot.situation.triggered.temperature-threshold");
    }

    @Test
    void resolvedTypeIncludesSituationId() {
        var event = new IoTSituationEvent(
                "temperature-threshold", "resolved", "sensor.outdoor",
                "tenant-1", Map.of(), NOW);
        assertThat(event.type()).isEqualTo("io.casehub.iot.situation.resolved.temperature-threshold");
    }

    @Test
    void tenancyIdReturnsConstructorValue() {
        var event = new IoTSituationEvent(
                "lock-state", "triggered", "lock.front_door",
                "my-tenant", Map.of(), NOW);
        assertThat(event.tenancyId()).isEqualTo("my-tenant");
    }

    @Test
    void tenancyIdIsNotSerialized() throws Exception {
        var event = new IoTSituationEvent(
                "lock-state", "triggered", "lock.front_door",
                "my-tenant", Map.of(), NOW);
        String json = MAPPER.writeValueAsString(event);
        assertThat(json).doesNotContain("tenancyId");
        assertThat(json).doesNotContain("my-tenant");
    }

    @Test
    void metadataIsPreserved() {
        var metadata = Map.<String, Object>of("temperature", 42.0, "threshold", 40.0, "direction", "ABOVE");
        var event = new IoTSituationEvent(
                "temperature-threshold", "triggered", "sensor.outdoor",
                "tenant-1", metadata, NOW);
        assertThat(event.metadata()).containsEntry("temperature", 42.0);
        assertThat(event.metadata()).containsEntry("direction", "ABOVE");
    }
}
