package io.casehub.iot.homeassistant.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record HaDeviceRegistryDto(
    String id,
    @JsonProperty("area_id") String areaId
) {}
