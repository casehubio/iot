package io.casehub.iot.scenario;

import io.casehub.iot.api.spi.DeviceRegistry;
import io.casehub.yaml.plugin.api.Execute;
import io.casehub.yaml.plugin.api.Plugin;
import io.casehub.yaml.plugin.api.Required;
import io.casehub.yaml.plugin.api.Result;

import java.util.LinkedHashMap;
import java.util.Map;

@Plugin(value = "iot.state",
        description = "Reads current state of an IoT device from DeviceRegistry")
public record IoTStatePlugin(
        @Required String device) {

    @Execute
    public Result run(DeviceRegistry registry) {
        var optDevice = registry.findById(device);
        if (optDevice.isEmpty()) {
            return Result.failed("Device not found: " + device);
        }
        var entity = optDevice.get();

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("deviceId", entity.deviceId());
        output.put("deviceClass", entity.deviceClass().name());
        output.put("providerId", entity.providerId());
        output.put("capabilities", entity.capabilities());
        if (entity.location() != null) {
            output.put("location", entity.location());
        }

        return Result.of(output);
    }
}
