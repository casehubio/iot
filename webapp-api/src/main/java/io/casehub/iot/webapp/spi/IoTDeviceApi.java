package io.casehub.iot.webapp.spi;

import io.casehub.iot.webapp.rest.CommandRequest;
import io.casehub.iot.webapp.rest.CommandResponse;
import io.casehub.iot.webapp.rest.DeviceResponse;
import io.casehub.iot.webapp.view.StateHistoryView;
import io.casehub.platform.api.mcp.ContextParam;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PathParam;
import io.casehub.platform.api.mcp.PlatformMutation;
import io.casehub.platform.api.mcp.PlatformQuery;
import io.casehub.platform.api.mcp.RestPath;
import io.casehub.platform.api.mcp.RestStatus;

import java.time.Instant;
import java.util.List;

@McpDomain(value = "iot/devices", basePath = "/api/devices")
public interface IoTDeviceApi {

    @PlatformQuery("List devices with optional filtering")
    @RestPath("/")
    List<DeviceResponse> listDevices(String deviceClass, String providerId, Boolean available,
                                     @ContextParam("tenancyId") String tenancyId);

    @PlatformQuery("Get device by ID")
    @RestPath("/{deviceId}")
    DeviceResponse getDevice(@PathParam String deviceId,
                             @ContextParam("tenancyId") String tenancyId);

    @PlatformMutation("Dispatch a command to a device")
    @RestPath("/{deviceId}/commands")
    @RestStatus(201)
    CommandResponse dispatchCommand(@PathParam String deviceId,
                                    CommandRequest command,
                                    @ContextParam("tenancyId") String tenancyId);

    @PlatformQuery("Get device state history")
    @RestPath("/{deviceId}/history")
    List<StateHistoryView> getDeviceHistory(@PathParam String deviceId,
                                            Instant from, Instant to, Integer limit,
                                            @ContextParam("tenancyId") String tenancyId);
}
