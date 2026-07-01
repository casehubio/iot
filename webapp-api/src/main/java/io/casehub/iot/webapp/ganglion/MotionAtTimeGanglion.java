package io.casehub.iot.webapp.ganglion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.ras.api.DetectionResult;
import io.casehub.ras.api.JavaSwitchGanglion;
import io.casehub.ras.api.SituationContext;
import io.cloudevents.CloudEvent;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class MotionAtTimeGanglion extends JavaSwitchGanglion {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LocalTime restrictedStart;
    private final LocalTime restrictedEnd;

    public MotionAtTimeGanglion() {
        this(LocalTime.of(23, 0), LocalTime.of(6, 0));
    }

    public MotionAtTimeGanglion(LocalTime restrictedStart, LocalTime restrictedEnd) {
        super("motion-at-time", Set.of("io.casehub.iot.state_change.presence_sensor"));
        this.restrictedStart = restrictedStart;
        this.restrictedEnd = restrictedEnd;
    }

    @Override
    protected DetectionResult evaluate(CloudEvent event, SituationContext context) {
        try {
            var data = event.getData();
            if (data == null) return noise();

            var root = MAPPER.readTree(data.toBytes());
            var after = root.path("after");
            if (after.isMissingNode()) return noise();

            var isPresent = after.path("isPresent").asBoolean(false);
            if (!isPresent) return noise();

            var eventTime = event.getTime();
            if (eventTime == null) return noise();

            var localTime = OffsetDateTime.parse(eventTime.toString()).toLocalTime();

            if (isWithinRestrictedHours(localTime)) {
                double confidence = 0.75;
                return detected(confidence, Map.of(
                        "eventTime", localTime.toString(),
                        "restrictedPeriod", restrictedStart + " - " + restrictedEnd));
            }

            return noise();
        } catch (Exception e) {
            return noise();
        }
    }

    private boolean isWithinRestrictedHours(LocalTime time) {
        if (restrictedStart.isBefore(restrictedEnd)) {
            // Same day range (e.g., 08:00 - 17:00)
            return !time.isBefore(restrictedStart) && !time.isAfter(restrictedEnd);
        } else {
            // Overnight range (e.g., 23:00 - 06:00)
            return !time.isBefore(restrictedStart) || !time.isAfter(restrictedEnd);
        }
    }
}
