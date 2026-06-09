package io.casehub.iot.api;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

public class PowerSensor extends DeviceEntity {

    public static final String CAP_POWER = "power";
    public static final String CAP_ENERGY = "energy";

    private final BigDecimal power;
    private final BigDecimal energy;

    private PowerSensor(Builder builder) {
        super(builder);
        this.power = builder.power;
        this.energy = builder.energy;
    }

    public Optional<BigDecimal> power() {
        return Optional.ofNullable(power);
    }

    public Optional<BigDecimal> energy() {
        return Optional.ofNullable(energy);
    }

    @Override
    public Map<String, Object> capabilities() {
        Map<String, Object> caps = super.capabilities();
        caps.put(CAP_POWER, power);
        caps.put(CAP_ENERGY, energy);
        return caps;
    }

    public PowerSensor.Builder toBuilder() {
        return PowerSensor.builder()
            .deviceId(deviceId()).deviceClass(deviceClass()).label(label())
            .available(available()).lastUpdated(lastUpdated()).tenancyId(tenancyId())
            .power(power).energy(energy);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder extends DeviceEntity.Builder<PowerSensor, Builder> {
        private BigDecimal power;
        private BigDecimal energy;

        public Builder power(BigDecimal power) {
            this.power = power;
            return this;
        }

        public Builder energy(BigDecimal energy) {
            this.energy = energy;
            return this;
        }

        @Override
        protected Builder self() {
            return this;
        }

        @Override
        public PowerSensor build() {
            return new PowerSensor(this);
        }
    }
}
