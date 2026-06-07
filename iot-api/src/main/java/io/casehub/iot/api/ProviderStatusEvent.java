package io.casehub.iot.api;

public record ProviderStatusEvent(
    String providerId,
    ProviderStatus previousStatus,
    ProviderStatus currentStatus
) {}
