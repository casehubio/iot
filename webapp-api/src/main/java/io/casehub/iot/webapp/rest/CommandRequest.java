package io.casehub.iot.webapp.rest;

import java.util.Map;

/**
 * REST request record for {@code POST /api/devices/{id}/commands}.
 *
 * <p>Carries action + parameters for device command dispatch.
 */
public record CommandRequest(
        String action,
        Map<String, Object> parameters
) {
}
