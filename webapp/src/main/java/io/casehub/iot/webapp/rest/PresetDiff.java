package io.casehub.iot.webapp.rest;

import java.util.List;

public record PresetDiff(List<DeviceChange> changes) {
    public PresetDiff {
        changes = List.copyOf(changes);
    }

    public record DeviceChange(
        String deviceId,
        String deviceClass,
        List<PropertyChange> properties
    ) {
        public DeviceChange {
            properties = List.copyOf(properties);
        }
    }

    public record PropertyChange(String property, Object current, Object desired) {}
}
