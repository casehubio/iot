package io.casehub.iot.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.time.ZoneOffset;
import java.util.UUID;

@ApplicationScoped
public class IoTCloudEventAdapter {

    private static final Logger LOG = Logger.getLogger(IoTCloudEventAdapter.class);
    private static final URI SOURCE = URI.create("/casehub-iot");
    private static final String TYPE_PREFIX = "io.casehub.iot.state_change.";

    private final Event<CloudEvent> cloudEvents;
    private final ObjectMapper objectMapper;

    @Inject
    public IoTCloudEventAdapter(Event<CloudEvent> cloudEvents, ObjectMapper objectMapper) {
        this.cloudEvents = cloudEvents;
        this.objectMapper = objectMapper;
    }

    void onStateChange(@ObservesAsync StateChangeEvent event) {
        String deviceClass = event.after().deviceClass().name().toLowerCase();
        byte[] data;
        try {
            data = objectMapper.writeValueAsBytes(event);
        } catch (JsonProcessingException e) {
            LOG.warnf("Failed to serialise StateChangeEvent for CloudEvent — device=%s: %s",
                    event.after().deviceId(), e.getMessage());
            data = new byte[0];
        }

        CloudEventBuilder builder = CloudEventBuilder.v1()
                .withId(UUID.randomUUID().toString())
                .withType(TYPE_PREFIX + deviceClass)
                .withSource(SOURCE)
                .withSubject("device/" + event.after().deviceId())
                .withTime(event.occurredAt().atOffset(ZoneOffset.UTC))
                .withData("application/json", data)
                .withExtension("providerid", event.providerId());

        if (event.after().tenancyId() != null) {
            builder = builder.withExtension("tenancyid", event.after().tenancyId());
        }

        cloudEvents.fireAsync(builder.build())
                .exceptionally(ex -> {
                    LOG.warnf(ex, "CloudEvent dispatch failed for device=%s",
                            event.after().deviceId());
                    return null;
                });
    }
}
