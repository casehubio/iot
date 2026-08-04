package io.casehub.iot.api.spi;

import io.casehub.iot.api.DeviceEntity;

import java.time.Instant;
import java.util.List;

public interface DeviceStateHistoryProvider {

    record HistoryEntry(
        String deviceId,
        String deviceClass,
        DeviceEntity stateSnapshot,
        List<String> changedCapabilities,
        Instant occurredAt
    ) {}

    List<HistoryEntry> findHistory(String deviceId, String tenancyId, Instant from, Instant to, int limit);
}
