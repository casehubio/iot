package io.casehub.iot.testing;

import io.casehub.iot.api.DeviceEntity;
import io.casehub.iot.api.SwitchDevice;
import io.casehub.iot.api.StateChangeEvent;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class StateChangeEventPublisherTest {

    @ApplicationScoped
    static class CapturedEvents {
        private final List<StateChangeEvent> received = new CopyOnWriteArrayList<>();

        void onEvent(@ObservesAsync StateChangeEvent event) {
            received.add(event);
        }

        List<StateChangeEvent> getAll() { return List.copyOf(received); }
        void clear() { received.clear(); }
    }

    @Inject StateChangeEventPublisher publisher;
    @Inject CapturedEvents captured;

    @BeforeEach
    void resetObserver() {
        captured.clear();
    }

    @Test
    void publishFiresEventWithAutoCalculatedChangedCapabilities() throws Exception {
        var before = Fixtures.hallwaySwitch();
        var after = before.toBuilder().on(true).build();

        publisher.publish(before, after, "test").toCompletableFuture().join();

        assertThat(captured.getAll()).hasSize(1);
        var event = captured.getAll().get(0);
        assertThat(event.changedCapabilities()).containsExactly(SwitchDevice.CAP_ON);
        assertThat(event.providerId()).isEqualTo("test");
        assertThat(event.before()).isEqualTo(before);
        assertThat(event.after()).isEqualTo(after);
    }

    @Test
    void publishWithNoChangeProducesEmptyChangedCapabilities() throws Exception {
        // Use SwitchDevice (non-extensible, has toBuilder) to test no-change case
        var device = Fixtures.hallwaySwitch();

        publisher.publish(device, device.toBuilder().build(), "test")
            .toCompletableFuture().join();

        assertThat(captured.getAll().get(0).changedCapabilities()).isEmpty();
    }

    @Test
    void publishWithAvailabilityChangeIncludesCapAvailable() throws Exception {
        var before = Fixtures.hallwaySwitch();
        var after = before.toBuilder().available(false).build();

        publisher.publish(before, after, "test").toCompletableFuture().join();

        assertThat(captured.getAll().get(0).changedCapabilities())
            .containsExactly(DeviceEntity.CAP_AVAILABLE);
    }

    @Test
    void multiplePublishesAreAllCaptured() throws Exception {
        var sw = Fixtures.hallwaySwitch();
        publisher.publish(sw, sw.toBuilder().on(true).build(), "test")
            .toCompletableFuture().join();
        publisher.publish(sw, sw.toBuilder().available(false).build(), "test")
            .toCompletableFuture().join();

        assertThat(captured.getAll()).hasSize(2);
    }
}
