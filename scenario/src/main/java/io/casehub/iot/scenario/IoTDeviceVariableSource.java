package io.casehub.iot.scenario;

import io.casehub.iot.api.DeviceEntity;
import io.casehub.iot.api.spi.DeviceRegistry;
import io.casehub.yaml.plugin.api.DomainVariableSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.Map;

@ApplicationScoped
public class IoTDeviceVariableSource implements DomainVariableSource {

    private final DeviceRegistry registry;

    @Inject
    public IoTDeviceVariableSource(DeviceRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String prefix() {
        return "device";
    }

    @Override
    public Object resolve(String path) {
        int dot = path.indexOf('.');
        String deviceId = dot < 0 ? path : path.substring(0, dot);

        var optDevice = registry.findById(deviceId);
        if (optDevice.isEmpty()) return null;
        var entity = optDevice.get();

        if (dot < 0) {
            return toMap(entity);
        }

        String rest = path.substring(dot + 1);
        return switch (rest) {
            case "capabilities" -> entity.capabilities();
            case "deviceClass" -> entity.deviceClass().name();
            case "providerId" -> entity.providerId();
            case "location" -> entity.location();
            default -> {
                if (rest.startsWith("capabilities.")) {
                    String capKey = rest.substring("capabilities.".length());
                    yield entity.capabilities().get(capKey);
                }
                yield null;
            }
        };
    }

    private Map<String, Object> toMap(DeviceEntity entity) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("deviceId", entity.deviceId());
        map.put("deviceClass", entity.deviceClass().name());
        map.put("providerId", entity.providerId());
        map.put("capabilities", entity.capabilities());
        if (entity.location() != null) map.put("location", entity.location());
        return map;
    }
}
