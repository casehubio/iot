package io.casehub.iot.homeassistant.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record HaStateDto(
    @JsonProperty("entity_id") String entityId,
    String state,
    @JsonProperty("last_updated") String lastUpdated,
    @JsonProperty("last_changed") String lastChanged,
    Map<String, Object> attributes
) {}
