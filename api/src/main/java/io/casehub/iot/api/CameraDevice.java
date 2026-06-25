package io.casehub.iot.api;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;

import java.util.Map;

@JsonDeserialize(builder = CameraDevice.Builder.class)
public class CameraDevice extends DeviceEntity {

    public static final String CAP_STREAMING = "isStreaming";

    private final boolean streaming;

    private CameraDevice(Builder builder) {
        super(builder);
        this.streaming = builder.streaming;
    }

    public boolean isStreaming() {
        return streaming;
    }

    @Override
    public Map<String, Object> capabilities() {
        Map<String, Object> caps = super.capabilities();
        caps.put(CAP_STREAMING, streaming);
        return caps;
    }

    public CameraDevice.Builder toBuilder() {
        return CameraDevice.builder()
            .deviceId(deviceId()).deviceClass(deviceClass()).label(label())
            .available(available()).lastUpdated(lastUpdated()).tenancyId(tenancyId()).providerId(providerId())
            .streaming(streaming);
    }

    public static Builder builder() {
        return new Builder();
    }

    @JsonPOJOBuilder(withPrefix = "")
    public static final class Builder extends DeviceEntity.Builder<CameraDevice, Builder> {
        private boolean streaming;

        public Builder streaming(boolean streaming) {
            this.streaming = streaming;
            return this;
        }

        @Override
        protected Builder self() {
            return this;
        }

        @Override
        public CameraDevice build() {
            return new CameraDevice(this);
        }
    }
}
