package io.casehub.iot.webapp.rest;

import io.casehub.iot.api.CommandResult;

/**
 * REST response record wrapping CommandResult.
 *
 * <p>Used by {@code POST /api/devices/{id}/commands}.
 */
public record CommandResponse(
        String deviceId,
        String action,
        CommandResult result,
        String correlationId
) {
}
