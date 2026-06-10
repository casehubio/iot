package io.casehub.iot.openhab.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenHabStatePayloadDto(String type, String value, String oldType, String oldValue) {}
