package io.casehub.iot.webapp.app.service;

import io.casehub.iot.api.StateChangeEvent;
import io.casehub.iot.webapp.app.simulation.SimulationPayloadConverter;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PlatformMutation;
import io.casehub.platform.api.mcp.PlatformQuery;
import io.casehub.platform.simulation.SimulationRuntime;
import io.casehub.platform.simulation.TemporalEventSink;
import io.casehub.platform.simulation.TemporalProfile;
import io.casehub.platform.simulation.TemporalSimulationDriver;
import io.casehub.platform.simulation.config.TemporalProfileRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

@McpDomain(value = "iot/simulation", basePath = "/api/simulation")
@ApplicationScoped
public class DefaultIoTSimulationApi {

    private static final Logger LOG = Logger.getLogger(DefaultIoTSimulationApi.class.getName());

    private final TemporalProfileRegistry                       profileRegistry;
    private final SimulationRuntime                             simulationRuntime;
    private final Event<StateChangeEvent>                       stateChangeEvents;
    private final SimulationPayloadConverter                    converter = new SimulationPayloadConverter();
    private final ReentrantLock                                 lock      = new ReentrantLock();
    private       TemporalSimulationDriver<Map<String, Object>> activeDriver;
    private       String                                        activeProfileName;

    @Inject
    public DefaultIoTSimulationApi(TemporalProfileRegistry profileRegistry,
                                   SimulationRuntime simulationRuntime,
                                   Event<StateChangeEvent> stateChangeEvents) {
        this.profileRegistry   = profileRegistry;
        this.simulationRuntime = simulationRuntime;
        this.stateChangeEvents = stateChangeEvents;
    }

    @PlatformMutation("Start a temporal simulation profile to generate IoT device events")
    public SimulationStatus start(SimulationStartRequest request) {
        lock.lock();
        try {
            if (activeDriver != null) {
                var state = activeDriver.state();
                if (state == TemporalSimulationDriver.State.RUNNING
                    || state == TemporalSimulationDriver.State.PAUSED) {
                    throw new IllegalStateException(
                            "Simulation already running (profile: " + activeProfileName
                            + "). Stop it first.");
                }
            }

            TemporalProfile<Map<String, Object>> profile = profileRegistry
                                                                   .resolve(request.profile())
                                                                   .orElseThrow(() -> new NotFoundException(
                                                                           "Profile not found: " + request.profile()
                                                                           + ". Available: " + profileRegistry.profileNames()));

            if (request.speed() != null && request.speed() > 0) {
                profile = new TemporalProfile<>(profile.name(), profile.qualifiedName(),
                                                profile.tenancyId(), profile.sequence(), profile.loop(),
                                                request.speed());
            }

            TemporalEventSink<Map<String, Object>> sink = (qn, label, payload) -> {
                StateChangeEvent event = converter.convert(payload);
                stateChangeEvents.fireAsync(event);
                LOG.fine(() -> "Simulation event: " + label
                               + " → " + event.after().deviceId());
            };

            activeDriver      = new TemporalSimulationDriver<>(sink, simulationRuntime);
            activeProfileName = request.profile();
            activeDriver.start(profile);

            LOG.info("Simulation started: profile=" + activeProfileName
                     + ", speed=" + activeDriver.speed());
            return buildStatus();
        } finally {
            lock.unlock();
        }
    }

    @PlatformMutation("Stop the active simulation")
    public void stop() {
        lock.lock();
        try {
            if (activeDriver == null) {
                throw new IllegalStateException("No active simulation");
            }
            activeDriver.stop();
            LOG.info("Simulation stopped: profile=" + activeProfileName);
            activeDriver      = null;
            activeProfileName = null;
        } finally {
            lock.unlock();
        }
    }

    @PlatformMutation("Change the speed of the active simulation")
    public void setSpeed(double speed) {
        lock.lock();
        try {
            if (activeDriver == null) {
                throw new IllegalStateException("No active simulation");
            }
            activeDriver.setSpeed(speed);
        } finally {
            lock.unlock();
        }
    }

    @PlatformQuery("Get the current simulation status")
    public SimulationStatus status() {
        lock.lock();
        try {
            return buildStatus();
        } finally {
            lock.unlock();
        }
    }

    @PlatformQuery("List available simulation profiles")
    public Set<String> profiles() {
        return profileRegistry.profileNames();
    }

    private SimulationStatus buildStatus() {
        if (activeDriver == null) {
            return SimulationStatus.IDLE;
        }
        var result = activeDriver.lastResult();
        return new SimulationStatus(
                activeProfileName,
                activeDriver.state().name(),
                activeDriver.speed(),
                result != null ? result.emittedCount() : 0,
                result != null ? result.failureCount() : 0);
    }

    public record SimulationStartRequest(String profile, Double speed) {}

    public record SimulationStatus(
            String profileName,
            String state,
            double speed,
            int emittedCount,
            int failureCount) {

        static final SimulationStatus IDLE = new SimulationStatus(null, "IDLE", 0, 0, 0);
    }
}
