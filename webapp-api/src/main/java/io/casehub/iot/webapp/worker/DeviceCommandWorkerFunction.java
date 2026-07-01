package io.casehub.iot.webapp.worker;

import io.casehub.iot.api.CommandResult;
import io.casehub.iot.api.DeviceCommand;
import io.casehub.iot.api.spi.DeviceProvider;
import io.casehub.iot.api.spi.DeviceRegistry;
import io.casehub.worker.api.WorkerResult;
import jakarta.enterprise.inject.Instance;

import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

public class DeviceCommandWorkerFunction implements Function<Map<String, Object>, WorkerResult> {

    private final Instance<DeviceProvider> providers;
    private final DeviceRegistry deviceRegistry;

    public DeviceCommandWorkerFunction(Instance<DeviceProvider> providers, DeviceRegistry deviceRegistry) {
        this.providers = providers;
        this.deviceRegistry = deviceRegistry;
    }

    @Override
    public WorkerResult apply(Map<String, Object> input) {
        String targetDeviceId = (String) input.get("targetDeviceId");
        if (targetDeviceId == null) {
            return WorkerResult.failed("Missing required field: targetDeviceId");
        }

        String action = (String) input.get("action");
        if (action == null) {
            return WorkerResult.failed("Missing required field: action",
                    Map.of("targetDeviceId", targetDeviceId));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> parameters = (Map<String, Object>) input.getOrDefault("parameters", Map.of());

        // Lookup device
        var deviceOpt = deviceRegistry.findById(targetDeviceId);
        if (deviceOpt.isEmpty()) {
            return WorkerResult.failed("Device not found: " + targetDeviceId,
                    Map.of("targetDeviceId", targetDeviceId));
        }

        var device = deviceOpt.get();

        // Find provider
        var providerOpt = providers.stream()
                .filter(p -> p.providerId().equals(device.providerId()))
                .findFirst();

        if (providerOpt.isEmpty()) {
            return WorkerResult.failed("Provider not found: " + device.providerId(),
                    Map.of("targetDeviceId", targetDeviceId, "providerId", device.providerId()));
        }

        var provider = providerOpt.get();

        // Dispatch command
        var command = new DeviceCommand(
                targetDeviceId,
                action,
                parameters,
                "casehub-iot-worker",
                UUID.randomUUID().toString()
        );

        CommandResult result = provider.dispatch(command).await().indefinitely();

        return WorkerResult.of(Map.of(
                "result", result.name(),
                "targetDeviceId", targetDeviceId,
                "action", action
        ));
    }
}
