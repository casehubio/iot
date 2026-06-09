package io.casehub.iot.testing;

import io.casehub.iot.api.DeviceClass;
import io.casehub.iot.api.DeviceEntity;
import io.casehub.iot.api.LightDevice;
import io.casehub.iot.api.SwitchDevice;
import io.casehub.iot.api.ThermostatDevice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

class MockDeviceRegistryTest {

    static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    MockDeviceRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new MockDeviceRegistry();
        registry.addDevice(SwitchDevice.builder()
            .deviceId("sw1").deviceClass(DeviceClass.SWITCH).label("Switch")
            .available(true).lastUpdated(NOW).tenancyId("t1").on(false).build());
        registry.addDevice(new LightDevice.Builder()
            .deviceId("l1").deviceClass(DeviceClass.LIGHT).label("Light")
            .available(true).lastUpdated(NOW).tenancyId("t1").on(false).build());
    }

    @Test
    void findByIdReturnsKnownDevice() {
        assertThat(registry.findById("sw1")).isPresent();
        assertThat(registry.findById("sw1").orElseThrow().deviceId()).isEqualTo("sw1");
    }

    @Test
    void findByIdReturnsEmptyForUnknown() {
        assertThat(registry.findById("unknown")).isEmpty();
    }

    @Test
    void findByClassFiltersToConcreteType() {
        assertThat(registry.findByClass(SwitchDevice.class)).hasSize(1);
        assertThat(registry.findByClass(LightDevice.class)).hasSize(1);
        assertThat(registry.findByClass(ThermostatDevice.class)).isEmpty();
        assertThat(registry.findByClass(DeviceEntity.class)).hasSize(2);
    }

    @Test
    void findByTenancyIdFilters() {
        assertThat(registry.findByTenancyId("t1")).hasSize(2);
        assertThat(registry.findByTenancyId("other")).isEmpty();
    }

    @Test
    void findAllReturnsAllDevices() {
        assertThat(registry.findAll()).hasSize(2);
    }

    @Test
    void refreshIsNoOp() {
        registry.refresh().await().indefinitely();
        assertThat(registry.findAll()).hasSize(2);
    }

    @Test
    void clearRemovesAllDevices() {
        registry.clear();
        assertThat(registry.findAll()).isEmpty();
    }

    @Test
    void addDevicesVarargs() {
        registry.clear();
        registry.addDevices(
            SwitchDevice.builder().deviceId("sw1").deviceClass(DeviceClass.SWITCH)
                .label("S").available(true).lastUpdated(NOW).tenancyId("t1").on(false).build(),
            new LightDevice.Builder().deviceId("l1").deviceClass(DeviceClass.LIGHT)
                .label("L").available(true).lastUpdated(NOW).tenancyId("t1").on(false).build());
        assertThat(registry.findAll()).hasSize(2);
    }

    @Test
    void addDevicesList() {
        registry.clear();
        registry.addDevices(Fixtures.standardHome());
        assertThat(registry.findAll()).hasSize(10);
    }
}
