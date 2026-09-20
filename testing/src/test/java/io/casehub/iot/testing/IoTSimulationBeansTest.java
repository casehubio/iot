package io.casehub.iot.testing;

import io.casehub.iot.api.DeviceClass;
import io.casehub.iot.api.StateChangeEvent;
import io.casehub.iot.api.SwitchDevice;
import io.casehub.platform.simulation.TemporalProfile;
import io.casehub.platform.simulation.TemporalSimulationDriver;
import io.casehub.platform.simulation.TimedEntry;
import io.casehub.platform.simulation.TimedSequence;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class IoTSimulationBeansTest {

    @Test
    void driverFiresStateChangeEvents() throws InterruptedException {
        var received = new ArrayList<StateChangeEvent>();
        var driver = new TemporalSimulationDriver<StateChangeEvent>(
            (qn, label, event) -> received.add(event), null);

        var event = createSwitchEvent("sw1", false, true);

        var profile = new TemporalProfile<>("test", "iot.state-change", "t1",
            new TimedSequence<>(List.of(
                new TimedEntry<>(event, Duration.ZERO, "switch-on"))),
            false, 1000.0);

        driver.start(profile);
        Thread.sleep(200);

        assertThat(received).hasSize(1);
        assertThat(received.get(0).after().deviceId()).isEqualTo("sw1");
        assertThat(driver.state()).isEqualTo(TemporalSimulationDriver.State.COMPLETED);
    }

    @Test
    void driverRespectsDelayAndSpeed() throws InterruptedException {
        var received = new ArrayList<StateChangeEvent>();
        var driver = new TemporalSimulationDriver<StateChangeEvent>(
            (qn, label, event) -> received.add(event), null);

        var event = createSwitchEvent("sw1", false, true);

        var profile = new TemporalProfile<>("test", "iot.state-change", "t1",
            new TimedSequence<>(List.of(
                new TimedEntry<>(event, Duration.ZERO, "first"),
                new TimedEntry<>(event, Duration.ofMillis(500), "second"))),
            false, 10.0);

        driver.start(profile);
        Thread.sleep(30);
        assertThat(received).hasSize(1);
        Thread.sleep(80);
        assertThat(received).hasSize(2);
    }

    @Test
    void driverLoopsWhenConfigured() throws InterruptedException {
        var received = new ArrayList<StateChangeEvent>();
        var driver = new TemporalSimulationDriver<StateChangeEvent>(
            (qn, label, event) -> received.add(event), null);

        var event = createSwitchEvent("sw1", false, true);

        var profile = new TemporalProfile<>("test", "iot.state-change", "t1",
            new TimedSequence<>(List.of(
                new TimedEntry<>(event, Duration.ZERO, "toggle"))),
            true, 1000.0);

        driver.start(profile);
        Thread.sleep(200);
        driver.stop();

        assertThat(received.size()).isGreaterThan(1);
    }

    private StateChangeEvent createSwitchEvent(String deviceId, boolean fromOn, boolean toOn) {
        var now = Instant.now();
        var before = SwitchDevice.builder().deviceId(deviceId).tenancyId("t1")
            .deviceClass(DeviceClass.SWITCH).lastUpdated(now)
            .providerId("sim").label("Switch").available(true).on(fromOn).build();
        var after = SwitchDevice.builder().deviceId(deviceId).tenancyId("t1")
            .deviceClass(DeviceClass.SWITCH).lastUpdated(now)
            .providerId("sim").label("Switch").available(true).on(toOn).build();
        return new StateChangeEvent(before, after, Set.of("on"), now, "sim");
    }
}
