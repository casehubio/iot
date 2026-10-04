package io.casehub.iot.desiredstate;

import io.casehub.iot.api.DeviceClass;
import java.util.Objects;

public record IoTOrderingEntry(DeviceClass before, DeviceClass after) {
    public IoTOrderingEntry {
        Objects.requireNonNull(before, "ordering 'before' required");
        Objects.requireNonNull(after, "ordering 'after' required");
        if (before == after) {
            throw new IllegalArgumentException(
                "Self-referencing ordering constraint: " + before);
        }
    }
}
