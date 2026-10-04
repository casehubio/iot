package io.casehub.iot.homeassistant.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record HaEntityRegistryDto(
    @JsonProperty("entity_id") String entityId,
    @JsonProperty("area_id") String areaId,
    @JsonProperty("device_id") String deviceId
) {}
