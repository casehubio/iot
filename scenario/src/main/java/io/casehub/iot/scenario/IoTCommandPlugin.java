package io.casehub.iot.scenario;

import io.casehub.iot.api.CommandResult;
import io.casehub.yaml.plugin.api.Execute;
import io.casehub.yaml.plugin.api.Optional;
import io.casehub.yaml.plugin.api.Plugin;
import io.casehub.yaml.plugin.api.Required;
import io.casehub.yaml.plugin.api.Result;

import java.util.Map;
import java.util.UUID;

@Plugin(value = "iot.command",
        description = "Dispatches a command to an IoT device via DeviceProvider")
public record IoTCommandPlugin(
        @Required String device,
        @Required String action,
        @Optional Map<String, Object> params,
        @Optional String correlationId) {

    @Execute
    public Result run(DeviceCommandDispatcher dispatcher) {
        String corrId = correlationId != null
                ? correlationId : UUID.randomUUID().toString();

        CommandResult result;
        try {
            result = dispatcher.dispatch(device, action, params, corrId);
        } catch (IllegalArgumentException e) {
            return Result.failed(e.getMessage());
        }

        return switch (result) {
            case SENT -> Result.of(Map.of(
                    "result", "SENT",
                    "device", device,
                    "action", action,
                    "correlationId", corrId));
            case FAILED -> Result.failed(
                    "Command " + action + " failed for device " + device);
            case TIMEOUT -> Result.failed(
                    "Command " + action + " timed out for device " + device);
        };
    }
}
