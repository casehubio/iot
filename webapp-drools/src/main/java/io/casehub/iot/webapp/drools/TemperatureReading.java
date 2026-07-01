package io.casehub.iot.webapp.drools;

import org.kie.api.definition.type.Role;
import org.kie.api.definition.type.Timestamp;

import java.math.BigDecimal;
import java.util.Objects;

@Role(Role.Type.EVENT)
@Timestamp("timestamp")
public class TemperatureReading {
    private final String deviceId;
    private final BigDecimal celsius;
    private final long timestamp;

    public TemperatureReading(String deviceId, BigDecimal celsius, long timestamp) {
        this.deviceId = Objects.requireNonNull(deviceId, "deviceId");
        this.celsius = Objects.requireNonNull(celsius, "celsius");
        this.timestamp = timestamp;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public BigDecimal getCelsius() {
        return celsius;
    }

    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TemperatureReading that = (TemperatureReading) o;
        return timestamp == that.timestamp
                && deviceId.equals(that.deviceId)
                && celsius.equals(that.celsius);
    }

    @Override
    public int hashCode() {
        return Objects.hash(deviceId, celsius, timestamp);
    }

    @Override
    public String toString() {
        return "TemperatureReading{deviceId='" + deviceId + "', celsius=" + celsius
                + ", timestamp=" + timestamp + '}';
    }
}
