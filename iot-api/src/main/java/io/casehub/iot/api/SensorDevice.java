package io.casehub.iot.api;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class SensorDevice extends DeviceEntity {

    public static final String CAP_NUMERIC_VALUE = "numericValue";
    public static final String CAP_BINARY_VALUE = "binaryValue";

    private final SensorType sensorType;
    private final BigDecimal numericValue;
    private final String unit;
    private final Boolean binaryValue;

    private SensorDevice(Builder builder) {
        super(builder);
        this.sensorType = Objects.requireNonNull(builder.sensorType, "sensorType");
        this.numericValue = builder.numericValue;
        this.unit = builder.unit;
        this.binaryValue = builder.binaryValue;
    }

    public SensorType sensorType() {
        return sensorType;
    }

    public Optional<BigDecimal> numericValue() {
        return Optional.ofNullable(numericValue);
    }

    public Optional<String> unit() {
        return Optional.ofNullable(unit);
    }

    public Optional<Boolean> binaryValue() {
        return Optional.ofNullable(binaryValue);
    }

    @Override
    public Map<String, Object> capabilities() {
        Map<String, Object> caps = super.capabilities();
        caps.put(CAP_NUMERIC_VALUE, numericValue);
        caps.put(CAP_BINARY_VALUE, binaryValue);
        return caps;
    }

    public SensorDevice.Builder toBuilder() {
        return SensorDevice.builder()
            .deviceId(deviceId()).deviceClass(deviceClass()).label(label())
            .available(available()).lastUpdated(lastUpdated()).tenancyId(tenancyId())
            .sensorType(sensorType).numericValue(numericValue).unit(unit).binaryValue(binaryValue);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder extends DeviceEntity.Builder<SensorDevice, Builder> {
        private SensorType sensorType;
        private BigDecimal numericValue;
        private String unit;
        private Boolean binaryValue;

        public Builder sensorType(SensorType sensorType) {
            this.sensorType = sensorType;
            return this;
        }

        public Builder numericValue(BigDecimal numericValue) {
            this.numericValue = numericValue;
            return this;
        }

        public Builder unit(String unit) {
            this.unit = unit;
            return this;
        }

        public Builder binaryValue(Boolean binaryValue) {
            this.binaryValue = binaryValue;
            return this;
        }

        @Override
        protected Builder self() {
            return this;
        }

        @Override
        public SensorDevice build() {
            return new SensorDevice(this);
        }
    }
}
