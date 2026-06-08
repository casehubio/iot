package io.casehub.iot.api;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record StateChangeEvent(
    DeviceEntity before,
    DeviceEntity after,
    Set<String> changedCapabilities,
    Instant occurredAt,
    String providerId
) {
    public StateChangeEvent {
        Objects.requireNonNull(after, "after");
        changedCapabilities = changedCapabilities == null ? Set.of() : Set.copyOf(changedCapabilities);
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(providerId, "providerId");
    }

    public static Set<String> deriveChangedCapabilities(
            DeviceEntity before, DeviceEntity after) {
        if (before.getClass() != after.getClass()) {
            throw new IllegalArgumentException(
                "Cannot derive changed capabilities across different types: "
                + before.getClass().getSimpleName() + " vs "
                + after.getClass().getSimpleName());
        }
        Map<String, Object> capsBefore = before.capabilities();
        Map<String, Object> capsAfter = after.capabilities();
        // Iterating capsAfter only is exhaustive: the same-type precondition guarantees
        // both maps have identical key sets (capabilities() is deterministic per type).
        // If a subclass ever produces variable key sets, extend this to a symmetric diff.
        Set<String> changed = new LinkedHashSet<>();
        for (var entry : capsAfter.entrySet()) {
            Object prev = capsBefore.get(entry.getKey());
            if (!Objects.equals(prev, entry.getValue())) {
                changed.add(entry.getKey());
            }
        }
        return Set.copyOf(changed);
    }
}
