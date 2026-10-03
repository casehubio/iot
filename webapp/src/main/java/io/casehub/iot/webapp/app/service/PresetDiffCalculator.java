package io.casehub.iot.webapp.app.service;

import io.casehub.iot.api.DeviceEntity;
import io.casehub.iot.api.spi.DeviceRegistry;
import io.casehub.iot.desiredstate.IoTDeviceGoal;
import io.casehub.iot.desiredstate.IoTGoals;
import io.casehub.iot.webapp.rest.PresetDiff;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@ApplicationScoped
public class PresetDiffCalculator {

    public PresetDiff calculate(IoTGoals goals, DeviceRegistry registry, String tenancyId) {
        List<PresetDiff.DeviceChange> changes = new ArrayList<>();

        for (IoTDeviceGoal goal : goals.devices()) {
            DeviceEntity device = registry.findById(goal.deviceId(), tenancyId).orElse(null);

            Map<String, Object> currentState = device != null
                ? device.capabilities() : Map.of();

            List<PresetDiff.PropertyChange> propChanges = new ArrayList<>();
            for (var entry : goal.config().entrySet()) {
                Object current = currentState.get(entry.getKey());
                Object desired = entry.getValue();
                if (!Objects.equals(current, desired)) {
                    propChanges.add(new PresetDiff.PropertyChange(entry.getKey(), current, desired));
                }
            }

            if (!propChanges.isEmpty()) {
                changes.add(new PresetDiff.DeviceChange(
                    goal.deviceId(), goal.deviceClass().name(), propChanges));
            }
        }

        return new PresetDiff(changes);
    }
}
