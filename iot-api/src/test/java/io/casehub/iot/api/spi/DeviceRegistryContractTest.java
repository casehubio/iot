package io.casehub.iot.api.spi;

import io.casehub.iot.api.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class DeviceRegistryContractTest {

    private static final Instant NOW = Instant.parse("2026-06-07T10:00:00Z");

    private SimpleDeviceRegistry registry;
    private SwitchDevice sw;
    private LightDevice light;

    static class SimpleDeviceRegistry implements DeviceRegistry {
        private final Map<String, DeviceEntity> devices = new LinkedHashMap<>();

        void addDevice(DeviceEntity device) { devices.put(device.deviceId(), device); }

        @Override public Optional<DeviceEntity> findById(String deviceId) {
            return Optional.ofNullable(devices.get(deviceId));
        }
        @Override @SuppressWarnings("unchecked")
        public <T extends DeviceEntity> List<T> findByClass(Class<T> deviceClass) {
            return devices.values().stream()
                .filter(deviceClass::isInstance).map(d -> (T) d).toList();
        }
        @Override public List<DeviceEntity> findByTenancyId(String tenancyId) {
            return devices.values().stream()
                .filter(d -> d.tenancyId().equals(tenancyId)).toList();
        }
        @Override public List<DeviceEntity> findAll() {
            return List.copyOf(devices.values());
        }
        @Override public void refresh() { /* no-op */ }
    }

    @BeforeEach
    void setUp() {
        registry = new SimpleDeviceRegistry();
        sw = SwitchDevice.builder()
            .deviceId("sw1").deviceClass(DeviceClass.SWITCH).label("Switch")
            .available(true).lastUpdated(NOW).tenancyId("t1").on(true).build();
        light = LightDevice.builder()
            .deviceId("l1").deviceClass(DeviceClass.LIGHT).label("Light")
            .available(true).lastUpdated(NOW).tenancyId("t2").on(true).brightness(200).build();
        registry.addDevice(sw);
        registry.addDevice(light);
    }

    @Test
    void findById() {
        assertThat(registry.findById("sw1")).hasValue(sw);
        assertThat(registry.findById("nonexistent")).isEmpty();
    }

    @Test
    void findByClass() {
        assertThat(registry.findByClass(SwitchDevice.class)).containsExactly(sw);
        assertThat(registry.findByClass(LightDevice.class)).containsExactly(light);
        assertThat(registry.findByClass(DeviceEntity.class)).containsExactly(sw, light);
    }

    @Test
    void findByTenancyId() {
        assertThat(registry.findByTenancyId("t1")).containsExactly(sw);
        assertThat(registry.findByTenancyId("t2")).containsExactly(light);
        assertThat(registry.findByTenancyId("unknown")).isEmpty();
    }

    @Test
    void findAll() {
        assertThat(registry.findAll()).containsExactly(sw, light);
    }
}
