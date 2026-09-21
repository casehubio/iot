package io.casehub.iot.webapp.app.service;

import io.casehub.iot.api.StateChangeEvent;
import io.casehub.platform.simulation.MapSimulationConfig;
import io.casehub.platform.simulation.SimulationRuntime;
import io.casehub.platform.simulation.TemporalProfile;
import io.casehub.platform.simulation.TimedEntry;
import io.casehub.platform.simulation.TimedSequence;
import io.casehub.platform.simulation.config.TemporalProfileRegistry;
import io.casehub.platform.simulation.inmem.InMemorySimulationCorpus;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.NotificationOptions;
import jakarta.enterprise.util.TypeLiteral;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.lang.annotation.Annotation;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultIoTSimulationApiTest {

    private DefaultIoTSimulationApi api;
    private List<StateChangeEvent>  firedEvents;

    @BeforeEach
    void setUp() {
        firedEvents = new ArrayList<>();

        List<TimedEntry<Map<String, Object>>> entries = List.of(
                new TimedEntry<>(
                        Map.<String, Object>of(
                                "deviceId", "light-1",
                                "deviceClass", "LIGHT",
                                "on", true),
                        Duration.ZERO,
                        "light-on"),
                new TimedEntry<>(
                        Map.<String, Object>of(
                                "deviceId", "presence-1",
                                "deviceClass", "PRESENCE_SENSOR",
                                "present", true),
                        Duration.ofSeconds(5),
                        "motion"));

        var profile = new TemporalProfile<>(
                "test-profile",
                "io.casehub.iot.state_change",
                "default-tenant",
                new TimedSequence<>(entries),
                true, 1000.0);

        TemporalProfileRegistry registry = new TemporalProfileRegistry(
                Map.of("test-profile", profile));

        SimulationRuntime runtime = new SimulationRuntime(
                MapSimulationConfig.of(Map.of()), new InMemorySimulationCorpus<>());

        Event<StateChangeEvent> mockEvent = new CapturingEvent();

        api = new DefaultIoTSimulationApi(registry, runtime, mockEvent);
    }

    @Test
    void statusIsIdleByDefault() {
        var status = api.status();
        assertThat(status.state()).isEqualTo("IDLE");
        assertThat(status.profileName()).isNull();
    }

    @Test
    void startsProfileAndEmitsEvents() throws Exception {
        var status = api.start(new DefaultIoTSimulationApi.SimulationStartRequest(
                "test-profile", 1000.0));

        assertThat(status.state()).isEqualTo("RUNNING");
        assertThat(status.profileName()).isEqualTo("test-profile");

        Thread.sleep(200);

        assertThat(firedEvents).isNotEmpty();
        assertThat(firedEvents.get(0).after().deviceId()).isEqualTo("light-1");

        api.stop();
    }

    @Test
    void stopHaltsSimulation() throws Exception {
        api.start(new DefaultIoTSimulationApi.SimulationStartRequest(
                "test-profile", 1000.0));

        Thread.sleep(100);
        api.stop();

        var status = api.status();
        assertThat(status.state()).isEqualTo("IDLE");
    }

    @Test
    void cannotStartWhileRunning() {
        api.start(new DefaultIoTSimulationApi.SimulationStartRequest(
                "test-profile", 1000.0));

        assertThatThrownBy(() -> api.start(
                new DefaultIoTSimulationApi.SimulationStartRequest("test-profile", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already running");

        api.stop();
    }

    @Test
    void cannotStopWithoutActive() {
        assertThatThrownBy(() -> api.stop())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No active simulation");
    }

    @Test
    void listsAvailableProfiles() {
        assertThat(api.profiles()).containsExactly("test-profile");
    }

    @Test
    void unknownProfileThrows() {
        assertThatThrownBy(() -> api.start(
                new DefaultIoTSimulationApi.SimulationStartRequest("nonexistent", null)))
                .isInstanceOf(jakarta.ws.rs.NotFoundException.class)
                .hasMessageContaining("Profile not found");
    }

    @Test
    void setSpeedUpdatesActiveDriver() {
        api.start(new DefaultIoTSimulationApi.SimulationStartRequest(
                "test-profile", 10.0));

        api.setSpeed(50.0);
        var status = api.status();
        assertThat(status.speed()).isEqualTo(50.0);

        api.stop();
    }

    private class CapturingEvent implements Event<StateChangeEvent> {
        @Override
        public void fire(StateChangeEvent event) {firedEvents.add(event);}

        @Override
        public <U extends StateChangeEvent> CompletionStage<U> fireAsync(U event) {
            firedEvents.add(event);
            return CompletableFuture.completedFuture(event);
        }

        @Override
        public <U extends StateChangeEvent> CompletionStage<U> fireAsync(U event, NotificationOptions options) {
            return fireAsync(event);
        }

        @Override
        public Event<StateChangeEvent> select(Annotation... qualifiers) {return this;}

        @Override
        public <U extends StateChangeEvent> Event<U> select(Class<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends StateChangeEvent> Event<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }
    }
}
