package io.casehub.iot.webapp.view;

import java.time.Instant;

public record ActiveSituationView(
    String situationId,
    String correlationKey,
    double confidence,
    int signalCount,
    Instant firstSignal,
    Instant lastSignal) {}
