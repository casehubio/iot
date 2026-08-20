package io.casehub.iot.mcp;

import io.casehub.iot.api.StateChangeEvent;

import java.time.Instant;
import java.util.Set;

public record StateChangeSummary(
    String deviceId,
    String deviceClass,
    String providerId,
    Set<String> changedCapabilities,
    Instant occurredAt
) {
    public static StateChangeSummary from(StateChangeEvent event) {
        return new StateChangeSummary(
            event.after().deviceId(),
            event.after().deviceClass().name(),
            event.providerId(),
            event.changedCapabilities(),
            event.occurredAt()
        );
    }
}
