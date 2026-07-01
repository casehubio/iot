package io.casehub.iot.webapp.app.rest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.iot.api.DeviceRegistry;
import io.casehub.iot.api.event.StateChangeEvent;
import io.casehub.iot.webapp.rest.DeviceResponse;
import io.casehub.platform.api.CurrentPrincipal;
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

/**
 * SSE endpoint for real-time device state updates.
 *
 * <p>Endpoint:
 * <ul>
 *   <li>{@code GET /api/devices/stream} — SSE stream of device state changes
 * </ul>
 *
 * <p>Protocol (per pages SSE spec):
 * <ul>
 *   <li>On connect: send {@code snapshot} operation with full device list
 *   <li>On state change: send {@code replace} operation for updated device
 * </ul>
 *
 * <p>Uses CDI {@code @ObservesAsync} to receive {@link StateChangeEvent} and
 * broadcast to all connected SSE clients.
 */
@Path("/api/devices/stream")
@ApplicationScoped
public class DeviceSseResource {

    @Inject
    DeviceRegistry deviceRegistry;

    @Inject
    CurrentPrincipal principal;

    @Inject
    ObjectMapper objectMapper;

    private BroadcastProcessor<String> broadcaster;

    @PostConstruct
    void init() {
        broadcaster = BroadcastProcessor.create();
    }

    /**
     * SSE stream endpoint.
     *
     * <p>On connection, sends a full snapshot of all devices. Subsequently,
     * pushes {@code replace} events for each state change observed via CDI.
     *
     * @return SSE stream of JSON-encoded operations
     */
    @GET
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    @RolesAllowed("iot-viewer")
    public Multi<String> stream() {
        // Send initial snapshot on connect
        Multi<String> snapshot = Multi.createFrom().item(() -> {
            try {
                var devices = deviceRegistry.findAll().stream()
                        .filter(d -> filterByTenancy(d.tenancyId()))
                        .map(d -> new DeviceResponse(
                                d.deviceId(),
                                d.providerId(),
                                d.tenancyId(),
                                d.deviceClass().name(),
                                d.name(),
                                d.location(),
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

        // Merge snapshot with broadcaster (state change events)
        return Multi.createBy().merging()
                .streams(snapshot, broadcaster);
    }

    /**
     * Observe state change events and broadcast to SSE clients.
     *
     * <p>Filters by current principal's tenancy before broadcasting. Each client
     * connection creates its own subscription to the broadcaster via {@link #stream()},
     * and CDI async events are shared across all subscribers.
     *
     * @param event state change event
     */
    void onStateChange(@ObservesAsync StateChangeEvent event) {
        // Filter by tenancy
        if (!filterByTenancy(event.device().tenancyId())) {
            return;
        }

        try {
            var deviceResponse = new DeviceResponse(
                    event.device().deviceId(),
                    event.device().providerId(),
                    event.device().tenancyId(),
                    event.device().deviceClass().name(),
                    event.device().name(),
                    event.device().location(),
                    event.device().available(),
                    event.device().capabilities(),
                    event.device().lastUpdated()
            );

            // Broadcast replace operation
            String operation = sseOperation("replace", List.of(deviceResponse));
            broadcaster.onNext(operation);

        } catch (JsonProcessingException e) {
            // Log and continue — don't break the stream for one bad event
            System.err.println("Failed to serialize state change event: " + e.getMessage());
        }
    }

    private boolean filterByTenancy(String deviceTenancyId) {
        return deviceTenancyId == null || deviceTenancyId.equals(principal.tenancyId());
    }

    /**
     * Build SSE operation message per pages protocol.
     *
     * <p>Format:
     * <pre>
     * {
     *   "operation": "snapshot" | "replace" | "append" | "remove",
     *   "data": [...]
     * }
     * </pre>
     *
     * @param operation operation type
     * @param data      operation payload
     * @return JSON-encoded SSE message
     */
    private String sseOperation(String operation, List<DeviceResponse> data) throws JsonProcessingException {
        Map<String, Object> message = new HashMap<>();
        message.put("operation", operation);
        message.put("data", data);
        return objectMapper.writeValueAsString(message);
    }
}
