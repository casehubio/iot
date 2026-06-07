package io.casehub.iot.api;

import java.time.Instant;
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
}
