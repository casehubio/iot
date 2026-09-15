package io.casehub.iot.webapp.view;

import java.time.Instant;
import java.util.List;

public record BridgeConnectionsView(
    boolean connected,
    List<TenancyConnection> tenancies) {

    public record TenancyConnection(
        String tenancyId,
        Instant connectedSince) {}
}
