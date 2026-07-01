package io.casehub.iot.webapp.drools;

import io.casehub.ras.drools.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Set;

@ApplicationScoped
public class MultiRoomMotionGanglion extends DroolsGanglion {

    private static final String DEFAULT_WINDOW_MINUTES = "2";
    private static final String DEFAULT_REQUIRED_DEVICES = "3";

    @Inject
    public MultiRoomMotionGanglion(DroolsSessionStore sessionStore) {
        this(sessionStore,
                Long.parseLong(System.getProperty(
                        "casehub.iot.ganglion.multi-room-motion.window-minutes",
                        DEFAULT_WINDOW_MINUTES)),
                Integer.parseInt(System.getProperty(
                        "casehub.iot.ganglion.multi-room-motion.required-devices",
                        DEFAULT_REQUIRED_DEVICES)));
    }

    public MultiRoomMotionGanglion(
            DroolsSessionStore sessionStore,
            long windowMinutes,
            int requiredDevices) {

        super(createConfig(windowMinutes, requiredDevices),
                sessionStore,
                List.of(new MotionEventExtractor()));
    }

    private static DroolsGanglionConfig createConfig(long windowMinutes, int requiredDevices) {
        String drl = String.format("""
                package io.casehub.iot.webapp.drools;

                import io.casehub.ras.api.DetectionResult;
                import io.casehub.ras.api.DetectionSignal;
                import java.util.Map;
                import java.util.Set;
                import io.casehub.iot.webapp.drools.MotionEvent;

                rule "Multi-room motion detected"
                when
                    accumulate(
                        $m: MotionEvent(motion == true, $deviceId: deviceId)
                            over window:time(%dm),
                        $deviceIds: collectSet($deviceId)
                    )
                    eval(((Set)$deviceIds).size() >= %d)
                then
                    Set deviceIdSet = (Set) $deviceIds;
                    channels["results"].send(new DetectionResult(
                        "multi-room-motion",
                        0.75,
                        DetectionSignal.DETECTED,
                        Map.of("distinctDevices", deviceIdSet.size())
                    ));
                end
                """, windowMinutes, requiredDevices);

        return new DroolsGanglionConfig(
                "multi-room-motion",
                Set.of("io.casehub.iot.state_change.presence_sensor"),
                SessionMode.LONG_LIVED,
                ClockMode.PSEUDO,
                List.of(),
                List.of(drl),
                ResultCollectionStrategy.HIGHEST_CONFIDENCE);
    }
}
