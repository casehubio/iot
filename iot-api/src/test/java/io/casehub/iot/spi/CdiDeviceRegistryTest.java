package io.casehub.iot.spi;

import io.casehub.iot.api.*;
import io.casehub.iot.api.spi.DeviceProvider;
import io.casehub.iot.api.spi.DeviceRegistry;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class CdiDeviceRegistryTest {

    private static final Instant NOW = Instant.parse("2026-06-07T10:00:00Z");

    @ApplicationScoped
    @Alternative
    @Priority(1)
    static class TestProvider implements DeviceProvider {
        @Override public String providerId() { return "test"; }
        @Override public List<DeviceEntity> discover() {
            return List.of(
                SwitchDevice.builder().deviceId("sw1").deviceClass(DeviceClass.SWITCH)
                    .label("Switch").available(true).lastUpdated(NOW).tenancyId("t1").on(true).build(),
                LightDevice.builder().deviceId("l1").deviceClass(DeviceClass.LIGHT)
                    .label("Light").available(true).lastUpdated(NOW).tenancyId("t2").on(true).brightness(200).build()
            );
        }
        @Override public CommandResult dispatch(DeviceCommand command) { return CommandResult.SENT; }
        @Override public ProviderStatus status() { return ProviderStatus.CONNECTED; }
    }

    @Inject
    DeviceRegistry registry;

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
    void stateChangeUpdatesRegistry() {
        var before = registry.findById("sw1").orElseThrow();
        var after = SwitchDevice.builder().deviceId("sw1").deviceClass(DeviceClass.SWITCH)
            .label("Switch").available(true).lastUpdated(Instant.now()).tenancyId("t1").on(false).build();

        ((CdiDeviceRegistry) registry).onStateChange(
            new StateChangeEvent(before, after, Set.of(SwitchDevice.CAP_ON), Instant.now(), "test"));

        var updated = registry.findById("sw1").orElseThrow();
        assertThat(((SwitchDevice) updated).isOn()).isFalse();
    }

    @Test
    void refreshRebuildsDeviceMap() {
        registry.refresh();
        assertThat(registry.findAll()).hasSize(2);
    }
}
