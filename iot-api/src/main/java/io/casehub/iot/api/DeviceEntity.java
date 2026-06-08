package io.casehub.iot.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public abstract class DeviceEntity {

    private final String deviceId;
    private final DeviceClass deviceClass;
    private final String label;
    private final boolean available;
    private final Instant lastUpdated;
    private final String tenancyId;

    protected DeviceEntity(Builder<?, ?> builder) {
        this.deviceId = Objects.requireNonNull(builder.deviceId, "deviceId");
        this.deviceClass = Objects.requireNonNull(builder.deviceClass, "deviceClass");
        this.label = Objects.requireNonNull(builder.label, "label");
        this.available = builder.available;
        this.lastUpdated = Objects.requireNonNull(builder.lastUpdated, "lastUpdated");
        this.tenancyId = Objects.requireNonNull(builder.tenancyId, "tenancyId");
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
    protected abstract static class Builder<T extends DeviceEntity, B extends Builder<T, B>> {
        String deviceId;
        DeviceClass deviceClass;
        String label;
        boolean available = true;
        Instant lastUpdated;
        String tenancyId;

        public B deviceId(String v) { this.deviceId = v; return self(); }
        public B deviceClass(DeviceClass v) { this.deviceClass = v; return self(); }
        public B label(String v) { this.label = v; return self(); }
        public B available(boolean v) { this.available = v; return self(); }
        public B lastUpdated(Instant v) { this.lastUpdated = v; return self(); }
        public B tenancyId(String v) { this.tenancyId = v; return self(); }

        protected B self() { return (B) this; }
        public abstract T build();
    }
}
