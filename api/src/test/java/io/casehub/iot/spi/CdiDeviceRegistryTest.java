package io.casehub.iot.spi;

import io.casehub.iot.api.*;
import io.casehub.iot.api.spi.DeviceProvider;
import io.casehub.iot.api.spi.DeviceRegistry;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Alternative;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@QuarkusTest
class CdiDeviceRegistryTest {

    private static final Instant NOW = Instant.parse("2026-06-07T10:00:00Z");

    @ApplicationScoped
    @Alternative
    @Priority(1)
    static class TestProvider implements DeviceProvider {
        @Override public String providerId() { return "test"; }
        @Override public Uni<List<DeviceEntity>> discover() {
            return Uni.createFrom().item(List.of(
                SwitchDevice.builder().deviceId("sw1").deviceClass(DeviceClass.SWITCH)
                    .label("Switch").available(true).lastUpdated(NOW).tenancyId("t1").providerId("test").on(true).build(),
                new LightDevice.Builder().deviceId("l1").deviceClass(DeviceClass.LIGHT)
                    .label("Light").available(true).lastUpdated(NOW).tenancyId("t2").providerId("test").on(true).brightness(200).build()
            ));
        }
        @Override public Uni<CommandResult> dispatch(DeviceCommand command) {
            return Uni.createFrom().item(CommandResult.SENT);
        }
        @Override public ProviderStatus status() { return ProviderStatus.CONNECTED; }
    }

    @Inject
    DeviceRegistry registry;

    @Inject
    Event<StateChangeEvent> events;

    @Test
    void discoversDevicesAtStartup() {
        assertThat(registry.findAll()).hasSize(2);
        assertThat(registry.findById("sw1")).isPresent();
        assertThat(registry.findById("l1")).isPresent();
    }

    @Test
    void findByClassFiltersCorrectly() {
        assertThat(registry.findByClass(SwitchDevice.class)).hasSize(1);
        assertThat(registry.findByClass(LightDevice.class)).hasSize(1);
        assertThat(registry.findByClass(DeviceEntity.class)).hasSize(2);
    }

    @Test
    void findByTenancyIdFiltersCorrectly() {
        assertThat(registry.findByTenancyId("t1")).hasSize(1);
        assertThat(registry.findByTenancyId("t2")).hasSize(1);
        assertThat(registry.findByTenancyId("unknown")).isEmpty();
    }

    @Test
    void stateChangeUpdatesRegistry() throws Exception {
        var before = (SwitchDevice) registry.findById("sw1").orElseThrow();
        var after = before.toBuilder().on(false).lastUpdated(Instant.now()).build();

        events.fireAsync(new StateChangeEvent(
            before, after,
            StateChangeEvent.deriveChangedCapabilities(before, after),
            Instant.now(), "test"))
        .toCompletableFuture().join();

        var updated = (SwitchDevice) registry.findById("sw1").orElseThrow();
        assertThat(updated.isOn()).isFalse();
    }

    @Test
    void refreshRebuildsDeviceMap() {
        registry.refresh().await().indefinitely();
        assertThat(registry.findAll()).hasSize(2);
    }

    @Test
    void perProviderRefreshRediscoversTargetProvider() {
        registry.refresh("test").await().indefinitely();
        assertThat(registry.findAll()).hasSize(2);
        assertThat(registry.findById("sw1")).isPresent();
        assertThat(registry.findById("l1")).isPresent();
    }

    @Test
    void perProviderRefreshThrowsForUnknownProvider() {
        assertThatThrownBy(() -> registry.refresh("nonexistent").await().indefinitely())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nonexistent");
    }
}
