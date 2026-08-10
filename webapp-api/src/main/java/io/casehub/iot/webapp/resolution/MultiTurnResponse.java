package io.casehub.iot.webapp.resolution;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MultiTurnResponse(
        TurnSignal signal,
        String reasoning,
        List<PlannedActionSpec> actions,
        String escalationReason,
        String informationNeeded
) {
    public MultiTurnResponse {
        actions = actions != null ? List.copyOf(actions) : List.of();
    }

    public static MultiTurnResponse tryParse(String json, ObjectMapper mapper) {
        try {
            return mapper.readValue(json, MultiTurnResponse.class);
        } catch (Exception e) {
            return null;
        }
    }
}
