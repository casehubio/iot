package io.casehub.iot.webapp.app.engine;

import io.casehub.iot.api.spi.DeviceRegistry;
import io.casehub.ras.api.CaseInputContributor;
import io.casehub.ras.api.CaseTriggerConfig;
import io.casehub.ras.api.SituationContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.Map;

@ApplicationScoped
public class IoTCaseInputContributor implements CaseInputContributor {

    private static final String DEVICE_PREFIX = "device/";

    private final DeviceRegistry deviceRegistry;

    @Inject
    public IoTCaseInputContributor(DeviceRegistry deviceRegistry) {
        this.deviceRegistry = deviceRegistry;
    }

    @Override
    public Map<String, Object> contribute(CaseTriggerConfig config, SituationContext context) {
        String ck = context.correlationKey();
        if (ck == null || !ck.startsWith(DEVICE_PREFIX)) {
            return Map.of();
        }
        String deviceId = ck.substring(DEVICE_PREFIX.length());
        return deviceRegistry.findById(deviceId)
                .map(device -> {
                    var data = new LinkedHashMap<String, Object>();
                    data.put("deviceId", deviceId);
                    data.put("deviceClass", device.deviceClass().name().toLowerCase());
                    if (device.location() != null) {
                        data.put("roomType", device.location());
                    }
                    data.put("eventTimestamp", context.lastSignal());
                    return Map.copyOf(data);
                })
                .orElse(Map.of());
    }
}
