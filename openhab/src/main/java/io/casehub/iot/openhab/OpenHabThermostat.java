package io.casehub.iot.openhab;

import io.casehub.iot.api.ThermostatDevice;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/**
 * OpenHAB supplement for {@link ThermostatDevice}.
 * Adds OpenHAB-specific fields: heating demand and cooling demand percentages.
 */
public class OpenHabThermostat extends ThermostatDevice {

    public static final String CAP_HEATING_DEMAND = "heatingDemand";
    public static final String CAP_COOLING_DEMAND = "coolingDemand";

    private final BigDecimal heatingDemand;
    private final BigDecimal coolingDemand;

    protected OpenHabThermostat(AbstractBuilder<?, ?> builder) {
        super(builder);
        this.heatingDemand = builder.heatingDemand;
        this.coolingDemand = builder.coolingDemand;
    }

    public Optional<BigDecimal> heatingDemand() {
        return Optional.ofNullable(heatingDemand);
    }

    public Optional<BigDecimal> coolingDemand() {
        return Optional.ofNullable(coolingDemand);
    }

    @Override
    public Map<String, Object> capabilities() {
        Map<String, Object> caps = super.capabilities();
        caps.put(CAP_HEATING_DEMAND, heatingDemand);
        caps.put(CAP_COOLING_DEMAND, coolingDemand);
        return caps;
    }

    public Builder toBuilder() {
        return new Builder()
                .deviceId(deviceId()).deviceClass(deviceClass()).label(label())
                .available(available()).lastUpdated(lastUpdated()).tenancyId(tenancyId())
                .currentTemperature(currentTemperature())
                .targetTemperature(targetTemperature())
                .mode(mode())
                .heatingDemand(heatingDemand).coolingDemand(coolingDemand);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder extends AbstractBuilder<OpenHabThermostat, Builder> {
        @Override
        protected Builder self() {
            return this;
        }

        @Override
        public OpenHabThermostat build() {
            return new OpenHabThermostat(this);
        }
    }

    public abstract static class AbstractBuilder<T extends OpenHabThermostat, B extends AbstractBuilder<T, B>>
            extends ThermostatDevice.AbstractBuilder<T, B> {
        BigDecimal heatingDemand;
        BigDecimal coolingDemand;

        public B heatingDemand(BigDecimal heatingDemand) {
            this.heatingDemand = heatingDemand;
            return self();
        }

        public B coolingDemand(BigDecimal coolingDemand) {
            this.coolingDemand = coolingDemand;
            return self();
        }
    }
}
