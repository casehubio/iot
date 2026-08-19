package io.casehub.iot.webapp.rest;

public record KpiMetric(
    String key,
    Object value,
    String label,
    String unit,
    String status
) {}
