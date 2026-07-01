package io.casehub.iot.webapp.drools;

import org.kie.api.definition.type.Role;
import org.kie.api.definition.type.Timestamp;

import java.util.Objects;

@Role(Role.Type.EVENT)
@Timestamp("timestamp")
public class MotionEvent {
    private final String deviceId;
    private final boolean motion;
    private final long timestamp;

    public MotionEvent(String deviceId, boolean motion, long timestamp) {
        this.deviceId = Objects.requireNonNull(deviceId, "deviceId");
        this.motion = motion;
        this.timestamp = timestamp;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public boolean isMotion() {
        return motion;
    }

    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MotionEvent that = (MotionEvent) o;
        return motion == that.motion
                && timestamp == that.timestamp
                && deviceId.equals(that.deviceId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(deviceId, motion, timestamp);
    }

    @Override
    public String toString() {
        return "MotionEvent{deviceId='" + deviceId + "', motion=" + motion
                + ", timestamp=" + timestamp + '}';
    }
}
