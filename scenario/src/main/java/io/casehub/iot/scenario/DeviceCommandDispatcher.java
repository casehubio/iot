package io.casehub.iot.scenario;

import io.casehub.iot.api.CommandResult;
import io.casehub.iot.api.DeviceCommand;
import io.casehub.iot.api.DeviceEntity;
import io.casehub.iot.api.spi.DeviceProvider;
import io.casehub.iot.api.spi.DeviceRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class DeviceCommandDispatcher {

    private final DeviceRegistry registry;
    private final Map<String, DeviceProvider> providers;

    @Inject
    public DeviceCommandDispatcher(DeviceRegistry registry,
                                   @Any Instance<DeviceProvider> providerBeans) {
        this.registry = registry;
        this.providers = new HashMap<>();
        providerBeans.forEach(p -> providers.put(p.providerId(), p));
    }

    DeviceCommandDispatcher(DeviceRegistry registry, List<DeviceProvider> providerList) {
        this.registry = registry;
        this.providers = new HashMap<>();
        providerList.forEach(p -> providers.put(p.providerId(), p));
    }

    public CommandResult dispatch(String deviceId, String action,
                                  Map<String, Object> params, String correlationId) {
        var optDevice = registry.findById(deviceId);
        if (optDevice.isEmpty()) {
            throw new IllegalArgumentException("Device not found: " + deviceId);
        }
        var entity = optDevice.get();

        var provider = providers.get(entity.providerId());
        if (provider == null) {
            throw new IllegalArgumentException("No provider for: " + entity.providerId());
        }

        var command = new DeviceCommand(
                deviceId, action, params != null ? params : Map.of(),
                "iot-scenario", correlationId);

        return provider.dispatch(command);
    }

    public Optional<DeviceEntity> findDevice(String deviceId) {
        return registry.findById(deviceId);
    }
}
