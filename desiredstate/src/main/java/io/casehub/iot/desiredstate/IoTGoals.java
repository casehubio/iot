package io.casehub.iot.desiredstate;

import java.util.List;
import java.util.Objects;

public record IoTGoals(String tenancyId, List<IoTDeviceGoal> devices, List<IoTOrderingEntry> ordering) {
    public IoTGoals {
        Objects.requireNonNull(tenancyId, "tenancyId required");
        devices = List.copyOf(devices);
        ordering = ordering != null ? List.copyOf(ordering) : List.of();
    }

    public IoTGoals(String tenancyId, List<IoTDeviceGoal> devices) {
        this(tenancyId, devices, List.of());
    }
}
