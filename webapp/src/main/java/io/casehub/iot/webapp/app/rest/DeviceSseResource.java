package io.casehub.iot.webapp.app.rest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.iot.api.spi.DeviceRegistry;
import io.casehub.iot.api.StateChangeEvent;
import io.casehub.iot.webapp.rest.DeviceResponse;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestStreamElementType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Path("/api/devices/stream")
@ApplicationScoped
public class DeviceSseResource {

    @Inject
    DeviceRegistry deviceRegistry;

    @Inject
    CurrentPrincipal principal;

    @Inject
    ObjectMapper objectMapper;

    BroadcastProcessor<DeviceResponse> broadcaster;

    @PostConstruct
    void init() {
        broadcaster = BroadcastProcessor.create();
    }

    @GET
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    @RolesAllowed("iot-viewer")
    public Multi<String> stream() {
        final String clientTenancyId = principal.tenancyId();

        Multi<String> snapshot = Multi.createFrom().item(() -> {
            try {
                var devices = deviceRegistry.findAll().stream()
                        .filter(d -> matchesTenancy(d.tenancyId(), clientTenancyId))
                        .map(d -> new DeviceResponse(
                                d.deviceId(),
                                d.providerId(),
                                d.tenancyId(),
                                d.deviceClass().name(),
                                d.label(),
                                null,
                                d.available(),
                                d.capabilities(),
                                d.lastUpdated()
                        ))
                        .toList();

                return sseOperation("snapshot", devices);
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to serialize snapshot", e);
            }
        });

        Multi<String> updates = broadcaster
                .filter(d -> matchesTenancy(d.tenancyId(), clientTenancyId))
                .map(d -> {
                    try {
                        return sseOperation("replace", List.of(d));
                    } catch (JsonProcessingException e) {
                        throw new RuntimeException("Failed to serialize state change", e);
                    }
                });

        return Multi.createBy().merging()
                .streams(snapshot, updates);
    }

    void onStateChange(@ObservesAsync StateChangeEvent event) {
        var device = event.after();
        var response = new DeviceResponse(
                device.deviceId(),
                device.providerId(),
                device.tenancyId(),
                device.deviceClass().name(),
                device.label(),
                null,
                device.available(),
                device.capabilities(),
                device.lastUpdated()
        );
        broadcaster.onNext(response);
    }

    private static boolean matchesTenancy(String deviceTenancyId, String clientTenancyId) {
        return deviceTenancyId.equals(clientTenancyId);
    }

    private String sseOperation(String operation, List<DeviceResponse> data) throws JsonProcessingException {
        Map<String, Object> message = new HashMap<>();
        message.put("operation", operation);
        message.put("data", data);
        return objectMapper.writeValueAsString(message);
    }
}
