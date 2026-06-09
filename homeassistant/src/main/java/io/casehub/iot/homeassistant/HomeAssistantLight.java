package io.casehub.iot.homeassistant;

import io.casehub.iot.api.LightDevice;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Home Assistant supplement for {@link LightDevice}.
 * Adds HA-specific fields that have no cross-vendor equivalent:
 * RGB colour, light effect, and supported colour modes.
 */
public class HomeAssistantLight extends LightDevice {

    public static final String CAP_RGB_COLOR = "rgbColor";
    public static final String CAP_EFFECT = "effect";
    public static final String CAP_SUPPORTED_COLOR_MODES = "supportedColorModes";

    private final int[] rgbColor;
    private final String effect;
    private final Set<String> supportedColorModes;

    protected HomeAssistantLight(AbstractBuilder<?, ?> builder) {
        super(builder);
        this.rgbColor = builder.rgbColor;
        this.effect = builder.effect;
        this.supportedColorModes = builder.supportedColorModes == null
                ? Set.of()
                : Set.copyOf(builder.supportedColorModes);
    }

    public Optional<int[]> rgbColor() {
        return Optional.ofNullable(rgbColor);
    }

    public Optional<String> effect() {
        return Optional.ofNullable(effect);
    }

    public Set<String> supportedColorModes() {
        return supportedColorModes;
    }

    @Override
    public Map<String, Object> capabilities() {
        Map<String, Object> caps = super.capabilities();
        caps.put(CAP_RGB_COLOR, rgbColor);
        caps.put(CAP_EFFECT, effect);
        caps.put(CAP_SUPPORTED_COLOR_MODES, supportedColorModes);
        return caps;
    }

    public Builder toBuilder() {
        return new Builder()
                .deviceId(deviceId()).deviceClass(deviceClass()).label(label())
                .available(available()).lastUpdated(lastUpdated()).tenancyId(tenancyId())
                .on(isOn()).brightness(brightness().orElse(null)).colorTemp(colorTemp().orElse(null))
                .rgbColor(rgbColor).effect(effect)
                .supportedColorModes(supportedColorModes.isEmpty() ? null : supportedColorModes);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder extends AbstractBuilder<HomeAssistantLight, Builder> {
        @Override
        protected Builder self() {
            return this;
        }

        @Override
        public HomeAssistantLight build() {
            return new HomeAssistantLight(this);
        }
    }

    public abstract static class AbstractBuilder<T extends HomeAssistantLight, B extends AbstractBuilder<T, B>>
            extends LightDevice.AbstractBuilder<T, B> {
        int[] rgbColor;
        String effect;
        Set<String> supportedColorModes;

        public B rgbColor(int[] rgbColor) {
            this.rgbColor = rgbColor;
            return self();
        }

        public B effect(String effect) {
            this.effect = effect;
            return self();
        }

        public B supportedColorModes(Set<String> supportedColorModes) {
            this.supportedColorModes = supportedColorModes;
            return self();
        }
    }
}
