package io.casehub.iot.webapp.ganglion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.ras.api.DetectionResult;
import io.casehub.ras.api.JavaSwitchGanglion;
import io.casehub.ras.api.SituationContext;
import io.cloudevents.CloudEvent;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class DeviceUnavailableGanglion extends JavaSwitchGanglion {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public DeviceUnavailableGanglion() {
        super("device-unavailable", Set.of(
                "io.casehub.iot.state_change.sensor",
                "io.casehub.iot.state_change.thermostat",
                "io.casehub.iot.state_change.presence_sensor",
                "io.casehub.iot.state_change.lock",
                "io.casehub.iot.state_change.power_sensor",
                "io.casehub.iot.state_change.switch",
                "io.casehub.iot.state_change.light",
                "io.casehub.iot.state_change.camera",
                "io.casehub.iot.state_change.climate"));
    }

    @Override
    protected DetectionResult evaluate(CloudEvent event, SituationContext context) {
        try {
            var data = event.getData();
            if (data == null) return noise();

            var root = MAPPER.readTree(data.toBytes());
            var after = root.path("after");
            if (after.isMissingNode()) return noise();

            var available = after.path("available");
            if (available.isMissingNode()) return noise();

            if (!available.asBoolean(true)) {
                double confidence = 0.85;
                return detected(confidence, Map.of(
                        "deviceClass", after.path("deviceClass").asText("UNKNOWN"),
                        "available", false));
            }

            return noise();
        } catch (Exception e) {
            return noise();
        }
    }
}
