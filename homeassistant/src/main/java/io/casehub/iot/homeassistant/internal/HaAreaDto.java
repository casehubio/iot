package io.casehub.iot.homeassistant.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record HaAreaDto(
    @JsonProperty("area_id") String areaId,
    String name,
    @JsonProperty("floor_id") String floorId
) {}
