package io.casehub.iot.bridge.agent;

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

/**
 * Receives commands from the cloud side and dispatches to the owning local
 * {@link DeviceProvider}. Commands arrive with local device IDs — the server
 * strips the tenancy prefix before sending. The target provider is resolved
 * via {@link DeviceRegistry#findById(String)} and the device's {@code providerId}.
 */
@ApplicationScoped
public class BridgeCommandDispatcher {

    private final Map<String, DeviceProvider> providerMap;
    private final DeviceRegistry registry;

    @Inject
    public BridgeCommandDispatcher(@Any Instance<DeviceProvider> discovered, DeviceRegistry registry) {
        this.providerMap = new HashMap<>();
        for (DeviceProvider p : discovered) {
            providerMap.put(p.providerId(), p);
        }
        this.registry = registry;
    }

    /** Test constructor. */
    BridgeCommandDispatcher(List<DeviceProvider> providers, DeviceRegistry registry) {
        this.providerMap = new HashMap<>();
        for (DeviceProvider p : providers) {
            providerMap.put(p.providerId(), p);
        }
        this.registry = registry;
    }

    public CommandResult dispatch(DeviceCommand command) {
        if (providerMap.isEmpty()) {
            return CommandResult.FAILED;
        }

        Optional<DeviceEntity> device = registry.findById(command.targetDeviceId());
        if (device.isEmpty()) {
            return CommandResult.FAILED;
        }

        DeviceProvider provider = providerMap.get(device.get().providerId());
        if (provider == null) {
            return CommandResult.FAILED;
        }

        return provider.dispatch(command);
    }
}
