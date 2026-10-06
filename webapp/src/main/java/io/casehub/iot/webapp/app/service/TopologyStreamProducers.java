package io.casehub.iot.webapp.app.service;

import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class TopologyStreamProducers {

    private final BroadcastProcessor<TopologyStreamEvent> broadcaster = BroadcastProcessor.create();

    @Produces
    @ApplicationScoped
    BroadcastProcessor<TopologyStreamEvent> topologyBroadcaster() {
        return broadcaster;
    }
}
