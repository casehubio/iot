package io.casehub.iot.webapp.app.service;

import io.casehub.iot.api.StateChangeEvent;
import io.casehub.iot.webapp.rest.TopologyResponse;
import io.casehub.platform.api.mcp.ContextParam;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PlatformQuery;
import io.casehub.platform.api.mcp.PlatformStream;
import io.casehub.platform.api.mcp.RestPath;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;

import java.util.List;

@McpDomain(value = "iot/topology", app = "iot", basePath = "/api/topology",
        summary = "IoT device topology — spatial tree and operational graph with drift visualization")
@ApplicationScoped
public class DefaultIoTTopologyApi {

    @Inject TopologyAssembler assembler;
    @Inject BroadcastProcessor<TopologyStreamEvent> broadcaster;

    @PlatformQuery("Get device topology with drift status and dependency edges")
    @RestPath("/")
    public TopologyResponse getTopology(@ContextParam("tenancyId") String tenancyId) {
        return assembler.assemble(tenancyId);
    }

    @PlatformStream("Stream topology node updates on device state changes")
    @RestPath("/stream")
    @RolesAllowed("iot-viewer")
    public Multi<TopologyStreamEvent> streamTopology(
            @ContextParam("tenancyId") String tenancyId) {
        Multi<TopologyStreamEvent> snapshot = Multi.createFrom().item(() -> {
            var response = assembler.assemble(tenancyId);
            return new TopologyStreamEvent(tenancyId, "snapshot", response.nodes());
        });

        Multi<TopologyStreamEvent> updates = broadcaster
                                                     .filter(e -> e.tenancyId().equals(tenancyId));

        return Multi.createBy().merging().streams(snapshot, updates);
    }

    void onStateChange(@ObservesAsync StateChangeEvent event) {
        var device = event.after();
        try {
            var node = assembler.reassembleNode(device.deviceId(), device.tenancyId());
            broadcaster.onNext(new TopologyStreamEvent(
                    device.tenancyId(), "update", List.of(node)));
        } catch (Exception ignored) {
        }
    }


}
