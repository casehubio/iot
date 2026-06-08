package io.casehub.iot.api;

import java.util.Map;
import java.util.Objects;

public class ThermostatDevice extends DeviceEntity {

    public static final String CAP_CURRENT_TEMPERATURE = "currentTemperature";
    public static final String CAP_TARGET_TEMPERATURE = "targetTemperature";
    public static final String CAP_MODE = "mode";

    private final Temperature currentTemperature;
    private final Temperature targetTemperature;
    private final ThermostatMode mode;

    protected ThermostatDevice(AbstractBuilder<?, ?> builder) {
        super(builder);
        this.currentTemperature = Objects.requireNonNull(builder.currentTemperature, "currentTemperature");
        this.targetTemperature = Objects.requireNonNull(builder.targetTemperature, "targetTemperature");
        this.mode = Objects.requireNonNull(builder.mode, "mode");
    }

    public Temperature currentTemperature() {
        return currentTemperature;
    }

    public Temperature targetTemperature() {
        return targetTemperature;
    }

    public ThermostatMode mode() {
        return mode;
    }

    @Override
    public Map<String, Object> capabilities() {
        Map<String, Object> caps = super.capabilities();
        caps.put(CAP_CURRENT_TEMPERATURE, currentTemperature);
        caps.put(CAP_TARGET_TEMPERATURE, targetTemperature);
        caps.put(CAP_MODE, mode);
        return caps;
    }

    public ThermostatDevice.Builder toBuilder() {
        return new Builder()
            .deviceId(deviceId()).deviceClass(deviceClass()).label(label())
            .available(available()).lastUpdated(lastUpdated()).tenancyId(tenancyId())
            .currentTemperature(currentTemperature)
            .targetTemperature(targetTemperature)
            .mode(mode);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder extends AbstractBuilder<ThermostatDevice, Builder> {
        @Override
        protected Builder self() {
            return this;
        }

        @Override
        public ThermostatDevice build() {
            return new ThermostatDevice(this);
        }
    }

    public abstract static class AbstractBuilder<T extends ThermostatDevice, B extends AbstractBuilder<T, B>>
            extends DeviceEntity.Builder<T, B> {
        Temperature currentTemperature;
        Temperature targetTemperature;
        ThermostatMode mode;

        public B currentTemperature(Temperature currentTemperature) {
            this.currentTemperature = currentTemperature;
            return self();
        }

        public B targetTemperature(Temperature targetTemperature) {
            this.targetTemperature = targetTemperature;
            return self();
        }

        public B mode(ThermostatMode mode) {
            this.mode = mode;
            return self();
        }
    }
}
