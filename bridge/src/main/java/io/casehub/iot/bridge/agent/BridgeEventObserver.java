package io.casehub.iot.bridge.agent;

import io.casehub.iot.api.ProviderStatusEvent;
import io.casehub.iot.api.StateChangeEvent;
import io.casehub.iot.api.bridge.BridgeMessage;
import io.casehub.iot.api.bridge.ConnectionState;
import io.casehub.iot.api.bridge.FilterAction;
import io.casehub.iot.api.bridge.FilterContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;

@ApplicationScoped
public class BridgeEventObserver {

    private static final Logger LOG = Logger.getLogger(BridgeEventObserver.class);

    @Inject
    BridgeFilterChain filterChain;

    @Inject
    BridgeConnectionManager connectionManager;

    @Inject
    BridgeAgentConfig config;

    @Inject
    BridgeEventStore eventStore;

    @Inject
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "casehub.iot.tenancy-id")
    String tenancyId;

    void onStateChange(@ObservesAsync StateChangeEvent event) {
        FilterContext ctx = new FilterContext(
                tenancyId,
                connectionManager.isConnected() ? ConnectionState.CONNECTED : ConnectionState.DISCONNECTED,
                event.providerId());
        FilterAction action = filterChain.execute(event, ctx).await().indefinitely();

        switch (action) {
            case FilterAction.Forward f -> {
                var msg = new BridgeMessage.StateChange(
                        tenancyId, Instant.now(), event);
                if (connectionManager.isConnected()) {
                    connectionManager.send(msg);
                } else {
                    eventStore.store(msg);
                }
            }
            case FilterAction.Suppress s ->
                    LOG.debugf("Event suppressed: %s", s.reason());
        }
    }

    void onProviderStatus(@ObservesAsync ProviderStatusEvent event) {
        if (!connectionManager.isConnected()) {
            return;
        }
        var msg = new BridgeMessage.ProviderStatusChange(
                tenancyId, Instant.now(), event);
        connectionManager.send(msg);
    }
}
