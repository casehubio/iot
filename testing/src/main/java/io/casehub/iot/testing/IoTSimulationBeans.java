package io.casehub.iot.testing;

import io.casehub.iot.api.StateChangeEvent;
import io.casehub.platform.simulation.SimulationRuntime;
import io.casehub.platform.simulation.TemporalDriverFactory;
import io.casehub.platform.simulation.TemporalSimulationDriver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

@ApplicationScoped
public class IoTSimulationBeans {

    @Inject Event<StateChangeEvent> stateChangeEvents;
    @Inject SimulationRuntime simulation;

    @Produces
    @ApplicationScoped
    public TemporalDriverFactory<StateChangeEvent> iotTemporalDriverFactory() {
        return () -> new TemporalSimulationDriver<>(
            (qn, label, event) -> stateChangeEvents.fireAsync(event),
            simulation);
    }
}
