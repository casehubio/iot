package io.casehub.iot.webapp.resolution;

public record ToolCall(String name, String arguments, String result, boolean isError) {}
