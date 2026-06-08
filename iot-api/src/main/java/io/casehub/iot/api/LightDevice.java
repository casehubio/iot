package io.casehub.iot.api;

import java.util.Map;
import java.util.Optional;

public class LightDevice extends DeviceEntity {

    public static final String CAP_ON = "isOn";
    public static final String CAP_BRIGHTNESS = "brightness";
    public static final String CAP_COLOR_TEMP = "colorTemp";

    private final boolean on;
    private final Integer brightness;
    private final Integer colorTemp;

    protected LightDevice(AbstractBuilder<?, ?> builder) {
        super(builder);
        this.on = builder.on;
        this.brightness = builder.brightness;
        this.colorTemp = builder.colorTemp;
    }

    public boolean isOn() {
        return on;
    }

    public Optional<Integer> brightness() {
        return Optional.ofNullable(brightness);
    }

    public Optional<Integer> colorTemp() {
        return Optional.ofNullable(colorTemp);
    }

    @Override
    public Map<String, Object> capabilities() {
        Map<String, Object> caps = super.capabilities();
        caps.put(CAP_ON, on);
        caps.put(CAP_BRIGHTNESS, brightness);
        caps.put(CAP_COLOR_TEMP, colorTemp);
        return caps;
    }

    public LightDevice.Builder toBuilder() {
        return new Builder()
            .deviceId(deviceId()).deviceClass(deviceClass()).label(label())
            .available(available()).lastUpdated(lastUpdated()).tenancyId(tenancyId())
            .on(on).brightness(brightness).colorTemp(colorTemp);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder extends AbstractBuilder<LightDevice, Builder> {
        @Override
        protected Builder self() {
            return this;
        }

        @Override
        public LightDevice build() {
            return new LightDevice(this);
        }
    }

    public abstract static class AbstractBuilder<T extends LightDevice, B extends AbstractBuilder<T, B>>
            extends DeviceEntity.Builder<T, B> {
        boolean on;
        Integer brightness;
        Integer colorTemp;

        public B on(boolean on) {
            this.on = on;
            return self();
        }

        public B brightness(Integer brightness) {
            this.brightness = brightness;
            return self();
        }

        public B colorTemp(Integer colorTemp) {
            this.colorTemp = colorTemp;
            return self();
        }
    }
}
