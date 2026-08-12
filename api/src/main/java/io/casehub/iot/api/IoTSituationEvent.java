package io.casehub.iot.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.casehub.platform.api.subscription.SubscribableEvent;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public final class IoTSituationEvent implements SubscribableEvent {

    private final String situationId;
    private final String changeType;
    private final String deviceId;
    private final String tenancyId;
    private final Map<String, Object> metadata;
    private final Instant occurredAt;

    public IoTSituationEvent(String situationId, String changeType, String deviceId,
                             String tenancyId, Map<String, Object> metadata, Instant occurredAt) {
        this.situationId = Objects.requireNonNull(situationId, "situationId");
        this.changeType = Objects.requireNonNull(changeType, "changeType");
        this.deviceId = Objects.requireNonNull(deviceId, "deviceId");
        this.tenancyId = Objects.requireNonNull(tenancyId, "tenancyId");
        this.metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    }

    @Override
    @JsonProperty
    public String type() {
        return "io.casehub.iot.situation." + changeType + "." + situationId;
    }

    @Override
    @JsonIgnore
    public String tenancyId() {
        return tenancyId;
    }

    @JsonProperty public String situationId() { return situationId; }
    @JsonProperty public String changeType() { return changeType; }
    @JsonProperty public String deviceId() { return deviceId; }
    @JsonProperty public Map<String, Object> metadata() { return metadata; }
    @JsonProperty public Instant occurredAt() { return occurredAt; }
}
