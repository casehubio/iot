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
public class LockStateGanglion extends JavaSwitchGanglion {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public LockStateGanglion() {
        super("lock-state", Set.of("io.casehub.iot.state_change.lock"));
    }

    @Override
    protected DetectionResult evaluate(CloudEvent event, SituationContext context) {
        try {
            var data = event.getData();
            if (data == null) return noise();

            var root = MAPPER.readTree(data.toBytes());
            var before = root.path("before");
            var after = root.path("after");

            if (before.isMissingNode() || after.isMissingNode()) return noise();

            var wasLocked = before.path("isLocked").asBoolean(false);
            var isLocked = after.path("isLocked").asBoolean(false);

            if (wasLocked && !isLocked) {
                double confidence = 0.8;
                return detected(confidence, Map.of(
                        "transition", "LOCKED -> UNLOCKED",
                        "wasLocked", true,
                        "isLocked", false));
            }

            return noise();
        } catch (Exception e) {
            return noise();
        }
    }
}
