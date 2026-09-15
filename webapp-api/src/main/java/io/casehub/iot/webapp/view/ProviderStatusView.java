package io.casehub.iot.webapp.view;

public record ProviderStatusView(
    String providerId,
    String status,
    int deviceCount) {}
