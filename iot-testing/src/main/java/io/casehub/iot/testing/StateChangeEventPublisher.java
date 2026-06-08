package io.casehub.iot.testing;

import io.casehub.iot.api.DeviceEntity;
import io.casehub.iot.api.StateChangeEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.concurrent.CompletionStage;

@ApplicationScoped
public class StateChangeEventPublisher {

    @Inject
    Event<StateChangeEvent> event;

    public CompletionStage<StateChangeEvent> publish(
            DeviceEntity before, DeviceEntity after, String providerId) {
        var sce = new StateChangeEvent(
            before, after,
            StateChangeEvent.deriveChangedCapabilities(before, after),
            Instant.now(), providerId);
        return event.fireAsync(sce);
    }
}
