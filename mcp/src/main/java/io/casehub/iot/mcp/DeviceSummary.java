package io.casehub.iot.mcp;

import io.casehub.iot.api.DeviceEntity;
import java.time.Instant;

record DeviceSummary(
    String deviceId,
    String deviceClass,
    String label,
    String providerId,
    String location,
    boolean available,
    Instant lastUpdated
) {
    static DeviceSummary from(DeviceEntity device) {
        return new DeviceSummary(
            device.deviceId(),
            device.deviceClass().name(),
            device.label(),
            device.providerId(),
            device.location(),
            device.available(),
            device.lastUpdated()
        );
    }
}
