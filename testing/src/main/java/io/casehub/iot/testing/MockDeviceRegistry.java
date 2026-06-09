package io.casehub.iot.testing;

import io.casehub.iot.api.DeviceEntity;
import io.casehub.iot.api.spi.DeviceRegistry;
import io.smallrye.mutiny.Uni;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Not thread-safe — designed for sequential test use only. */
public class MockDeviceRegistry implements DeviceRegistry {

    private final Map<String, DeviceEntity> devices = new LinkedHashMap<>();

    @Override
    public Optional<DeviceEntity> findById(String deviceId) {
        return Optional.ofNullable(devices.get(deviceId));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends DeviceEntity> List<T> findByClass(Class<T> deviceClass) {
        return devices.values().stream()
            .filter(deviceClass::isInstance)
            .map(d -> (T) d)
            .toList();
    }

    @Override
    public List<DeviceEntity> findByTenancyId(String tenancyId) {
        return devices.values().stream()
            .filter(d -> d.tenancyId().equals(tenancyId))
            .toList();
    }

    @Override
    public List<DeviceEntity> findAll() {
        return List.copyOf(devices.values());
    }

    @Override
    public Uni<Void> refresh() {
        return Uni.createFrom().voidItem(); /* no-op — populated programmatically */
    }

    public void addDevice(DeviceEntity device) {
        devices.put(device.deviceId(), device);
    }

    public void addDevices(DeviceEntity... devs) {
        Arrays.stream(devs).forEach(this::addDevice);
    }

    public void addDevices(List<DeviceEntity> devs) {
        devs.forEach(this::addDevice);
    }

    public void clear() {
        devices.clear();
    }
}
