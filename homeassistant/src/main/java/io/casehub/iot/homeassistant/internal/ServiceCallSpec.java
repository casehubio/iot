package io.casehub.iot.homeassistant.internal;

public record ServiceCallSpec(String domain, String service, HaServiceCallDto body) {}
