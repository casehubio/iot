package io.casehub.iot.homeassistant;

import io.casehub.iot.api.ThermostatDevice;

import java.util.Map;
import java.util.Optional;

/**
 * Home Assistant supplement for {@link ThermostatDevice}.
 * Adds HA-specific fields: preset mode, swing mode, and HVAC action.
 */
public class HomeAssistantThermostat extends ThermostatDevice {

    public static final String CAP_PRESET_MODE = "presetMode";
    public static final String CAP_SWING_MODE = "swingMode";
    public static final String CAP_HVAC_ACTION = "hvacAction";

    private final String presetMode;
    private final String swingMode;
    private final String hvacAction;

    protected HomeAssistantThermostat(AbstractBuilder<?, ?> builder) {
        super(builder);
        this.presetMode = builder.presetMode;
        this.swingMode = builder.swingMode;
        this.hvacAction = builder.hvacAction;
    }

    public Optional<String> presetMode() {
        return Optional.ofNullable(presetMode);
    }

    public Optional<String> swingMode() {
        return Optional.ofNullable(swingMode);
    }

    public Optional<String> hvacAction() {
        return Optional.ofNullable(hvacAction);
    }

    @Override
    public Map<String, Object> capabilities() {
        Map<String, Object> caps = super.capabilities();
        caps.put(CAP_PRESET_MODE, presetMode);
        caps.put(CAP_SWING_MODE, swingMode);
        caps.put(CAP_HVAC_ACTION, hvacAction);
        return caps;
    }

    public Builder toBuilder() {
        return new Builder()
                .deviceId(deviceId()).deviceClass(deviceClass()).label(label())
                .available(available()).lastUpdated(lastUpdated()).tenancyId(tenancyId())
                .currentTemperature(currentTemperature())
                .targetTemperature(targetTemperature())
                .mode(mode())
                .presetMode(presetMode).swingMode(swingMode).hvacAction(hvacAction);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder extends AbstractBuilder<HomeAssistantThermostat, Builder> {
        @Override
        protected Builder self() {
            return this;
        }

        @Override
        public HomeAssistantThermostat build() {
            return new HomeAssistantThermostat(this);
        }
    }

    public abstract static class AbstractBuilder<T extends HomeAssistantThermostat, B extends AbstractBuilder<T, B>>
            extends ThermostatDevice.AbstractBuilder<T, B> {
        String presetMode;
        String swingMode;
        String hvacAction;

        public B presetMode(String presetMode) {
            this.presetMode = presetMode;
            return self();
        }

        public B swingMode(String swingMode) {
            this.swingMode = swingMode;
            return self();
        }

        public B hvacAction(String hvacAction) {
            this.hvacAction = hvacAction;
            return self();
        }
    }
}
