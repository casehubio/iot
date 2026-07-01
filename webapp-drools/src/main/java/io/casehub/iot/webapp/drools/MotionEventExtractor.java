package io.casehub.iot.webapp.drools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.ras.drools.DroolsObjectExtractor;
import io.cloudevents.CloudEvent;

import java.util.List;
import java.util.Set;

public class MotionEventExtractor implements DroolsObjectExtractor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public Set<String> handledEventTypes() {
        return Set.of("io.casehub.iot.state_change.presence_sensor");
    }

    @Override
    public List<Object> extract(CloudEvent event) {
        try {
            var data = event.getData();
            if (data == null) return List.of();

            var root = MAPPER.readTree(data.toBytes());
            var after = root.path("after");
            if (after.isMissingNode()) return List.of();

            String deviceId = after.path("deviceId").asText(null);
            if (deviceId == null) return List.of();

            JsonNode motionNode = after.path("motion");
            if (!motionNode.isBoolean()) return List.of();

            boolean motion = motionNode.asBoolean();

            var eventTime = event.getTime();
            if (eventTime == null) return List.of();

            long timestamp = eventTime.toInstant().toEpochMilli();

            return List.of(new MotionEvent(deviceId, motion, timestamp));
        } catch (Exception e) {
            return List.of();
        }
    }
}
