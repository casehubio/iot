package io.casehub.iot.homeassistant.internal;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.LinkedHashMap;
import java.util.Map;

public record HaServiceCallDto(
    @JsonProperty("entity_id") String entityId,
    @JsonIgnore Map<String, Object> parameters
) {
    @JsonAnyGetter
    public Map<String, Object> flatParameters() {
        if (parameters == null) return Map.of();
        return parameters;
    }
}
