package io.casehub.iot.api;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.annotation.JsonTypeIdResolver;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@JsonTypeInfo(use = JsonTypeInfo.Id.CUSTOM, property = "@deviceType")
@JsonTypeIdResolver(DeviceTypeIdResolver.class)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY,
                getterVisibility = JsonAutoDetect.Visibility.NONE,
                isGetterVisibility = JsonAutoDetect.Visibility.NONE)
public abstract class DeviceEntity {

    private final String deviceId;
    private final DeviceClass deviceClass;
    private final String label;
    private final boolean available;
    private final Instant lastUpdated;
    private final String tenancyId;
    private final String providerId;
    private final String location;

    protected DeviceEntity(Builder<?, ?> builder) {
        this.deviceId = Objects.requireNonNull(builder.deviceId, "deviceId");
        this.deviceClass = Objects.requireNonNull(builder.deviceClass, "deviceClass");
        this.label = Objects.requireNonNull(builder.label, "label");
        this.available = builder.available;
        this.lastUpdated = Objects.requireNonNull(builder.lastUpdated, "lastUpdated");
        this.tenancyId = Objects.requireNonNull(builder.tenancyId, "tenancyId");
        this.providerId = Objects.requireNonNull(builder.providerId, "providerId");
        this.location = builder.location;
    }

    public static final String CAP_AVAILABLE = "available";

    public Map<String, Object> capabilities() {
        Map<String, Object> caps = new LinkedHashMap<>();
        caps.put(CAP_AVAILABLE, available);
        return caps;
    }

    public String deviceId() { return deviceId; }
    public DeviceClass deviceClass() { return deviceClass; }
    public String label() { return label; }
    public boolean available() { return available; }
    public Instant lastUpdated() { return lastUpdated; }
    public String tenancyId() { return tenancyId; }
    public String providerId() { return providerId; }
    public String location() { return location; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DeviceEntity that)) return false;
        return deviceId.equals(that.deviceId);
    }

    @Override
    public int hashCode() {
        return deviceId.hashCode();
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{deviceId='" + deviceId
            + "', deviceClass=" + deviceClass + ", label='" + label + "'}";
    }

    @SuppressWarnings("unchecked")
    @JsonIgnoreProperties(ignoreUnknown = true)
    public abstract static class Builder<T extends DeviceEntity, B extends Builder<T, B>> {
        String deviceId;
        DeviceClass deviceClass;
        String label;
        boolean available = true;
        Instant lastUpdated;
        String tenancyId;
        String providerId;
        String location;

        public B deviceId(String v) { this.deviceId = v; return self(); }
        public B deviceClass(DeviceClass v) { this.deviceClass = v; return self(); }
        public B label(String v) { this.label = v; return self(); }
        public B available(boolean v) { this.available = v; return self(); }
        public B lastUpdated(Instant v) { this.lastUpdated = v; return self(); }
        public B tenancyId(String v) { this.tenancyId = v; return self(); }
        public B providerId(String v) { this.providerId = v; return self(); }
        public B location(String v) { this.location = v; return self(); }

        protected B self() { return (B) this; }
        public abstract T build();
    }
}
