package io.casehub.iot.api;

import java.util.Map;

public class SwitchDevice extends DeviceEntity {

    public static final String CAP_ON = "isOn";

    private final boolean on;

    private SwitchDevice(Builder builder) {
        super(builder);
        this.on = builder.on;
    }

    public boolean isOn() {
        return on;
    }

    @Override
    public Map<String, Object> capabilities() {
        Map<String, Object> caps = super.capabilities();
        caps.put(CAP_ON, on);
        return caps;
    }

    public SwitchDevice.Builder toBuilder() {
        return SwitchDevice.builder()
            .deviceId(deviceId()).deviceClass(deviceClass()).label(label())
            .available(available()).lastUpdated(lastUpdated()).tenancyId(tenancyId())
            .on(on);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder extends DeviceEntity.Builder<SwitchDevice, Builder> {
        private boolean on;

        public Builder on(boolean on) {
            this.on = on;
            return this;
        }

        @Override
        protected Builder self() {
            return this;
        }

        @Override
        public SwitchDevice build() {
            return new SwitchDevice(this);
        }
    }
}
