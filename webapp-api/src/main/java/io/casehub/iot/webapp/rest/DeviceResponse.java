package io.casehub.iot.webapp.rest;

import java.time.Instant;
import java.util.Map;

/**
 * REST response record mapping DeviceEntity fields to JSON.
 *
 * <p>Used by {@code GET /api/devices} and {@code GET /api/devices/{id}}.
 */
public record DeviceResponse(
        String deviceId,
        String providerId,
        String tenancyId,
        String deviceClass,
        String name,
        String location,
        boolean available,
        Map<String, Object> capabilities,
        Instant lastUpdated
) {
}
