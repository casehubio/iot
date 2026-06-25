package io.casehub.iot.openhab;

import io.casehub.iot.api.DeviceClass;
import io.casehub.iot.api.SensorType;
import io.casehub.iot.api.Temperature;
import io.casehub.iot.api.ThermostatMode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Carries all resolved device fields between resolution and construction.
 *
 * <p>Both Equipment-based and Thing-based resolution strategies produce a
 * {@code ResolvedDeviceFields} instance; {@link OpenHabDeviceBuilder} then
 * constructs the correct {@link io.casehub.iot.api.DeviceEntity} subtype
 * from it. This eliminates duplication of device-construction logic across
 * resolution strategies.</p>
 */
public record ResolvedDeviceFields(
        String deviceId,
        String label,
        boolean available,
        Instant now,
        String tenancyId,
        DeviceClass deviceClass,
        // thermostat
        Temperature currentTemperature,
        Temperature targetTemperature,
        ThermostatMode mode,
        BigDecimal heatingDemand,
        BigDecimal coolingDemand,
        // light
        Boolean on,
        OpenHabHsbType hsb,
        Integer brightness,
        // lock
        Boolean locked,
        // cover
        Integer position,
        boolean isRollershutter,
        // media player
        Integer volume,
        // sensor
        SensorType sensorType,
        BigDecimal numericValue,
        String unit,
        // power sensor
        BigDecimal power,
        BigDecimal energy,
        // presence sensor
        Boolean present,
        // camera
        Boolean streaming
) {
    public ResolvedDeviceFields {
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(tenancyId, "tenancyId");
        Objects.requireNonNull(deviceClass, "deviceClass");
    }

    public ResolvedDeviceFields withAvailable(boolean newAvailable) {
        return new ResolvedDeviceFields(deviceId, label, newAvailable, now, tenancyId, deviceClass,
                currentTemperature, targetTemperature, mode, heatingDemand, coolingDemand,
                on, hsb, brightness, locked, position, isRollershutter, volume,
                sensorType, numericValue, unit, power, energy, present, streaming);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String deviceId;
        private String label;
        private boolean available = true;
        private Instant now;
        private String tenancyId;
        private DeviceClass deviceClass;
        // thermostat
        private Temperature currentTemperature;
        private Temperature targetTemperature;
        private ThermostatMode mode;
        private BigDecimal heatingDemand;
        private BigDecimal coolingDemand;
        // light
        private Boolean on;
        private OpenHabHsbType hsb;
        private Integer brightness;
        // lock
        private Boolean locked;
        // cover
        private Integer position;
        private boolean isRollershutter;
        // media player
        private Integer volume;
        // sensor
        private SensorType sensorType;
        private BigDecimal numericValue;
        private String unit;
        // power sensor
        private BigDecimal power;
        private BigDecimal energy;
        // presence sensor
        private Boolean present;
        // camera
        private Boolean streaming;

        public Builder deviceId(String deviceId) { this.deviceId = deviceId; return this; }
        public Builder label(String label) { this.label = label; return this; }
        public Builder available(boolean available) { this.available = available; return this; }
        public Builder now(Instant now) { this.now = now; return this; }
        public Builder tenancyId(String tenancyId) { this.tenancyId = tenancyId; return this; }
        public Builder deviceClass(DeviceClass deviceClass) { this.deviceClass = deviceClass; return this; }

        public Builder currentTemperature(Temperature v) { this.currentTemperature = v; return this; }
        public Builder targetTemperature(Temperature v) { this.targetTemperature = v; return this; }
        public Builder mode(ThermostatMode v) { this.mode = v; return this; }
        public Builder heatingDemand(BigDecimal v) { this.heatingDemand = v; return this; }
        public Builder coolingDemand(BigDecimal v) { this.coolingDemand = v; return this; }

        public Builder on(Boolean v) { this.on = v; return this; }
        public Builder hsb(OpenHabHsbType v) { this.hsb = v; return this; }
        public Builder brightness(Integer v) { this.brightness = v; return this; }

        public Builder locked(Boolean v) { this.locked = v; return this; }

        public Builder position(Integer v) { this.position = v; return this; }
        public Builder isRollershutter(boolean v) { this.isRollershutter = v; return this; }

        public Builder volume(Integer v) { this.volume = v; return this; }

        public Builder sensorType(SensorType v) { this.sensorType = v; return this; }
        public SensorType currentSensorType() { return this.sensorType; }
        public Builder numericValue(BigDecimal v) { this.numericValue = v; return this; }
        public Builder unit(String v) { this.unit = v; return this; }

        public Builder power(BigDecimal v) { this.power = v; return this; }
        public Builder energy(BigDecimal v) { this.energy = v; return this; }

        public Builder present(Boolean v) { this.present = v; return this; }
        public Builder streaming(Boolean v) { this.streaming = v; return this; }

        public ResolvedDeviceFields build() {
            return new ResolvedDeviceFields(
                    deviceId, label, available, now, tenancyId, deviceClass,
                    currentTemperature, targetTemperature, mode, heatingDemand, coolingDemand,
                    on, hsb, brightness,
                    locked,
                    position, isRollershutter,
                    volume,
                    sensorType, numericValue, unit,
                    power, energy,
                    present,
                    streaming
            );
        }
    }
}
