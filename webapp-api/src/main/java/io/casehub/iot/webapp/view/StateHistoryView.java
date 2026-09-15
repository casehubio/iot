package io.casehub.iot.webapp.view;

import java.time.Instant;
import java.util.List;

public record StateHistoryView(
    String deviceId,
    String deviceClass,
    Object stateSnapshot,
    List<String> changedCapabilities,
    Instant occurredAt) {}
