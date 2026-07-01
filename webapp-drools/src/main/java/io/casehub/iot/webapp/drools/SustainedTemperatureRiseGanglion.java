package io.casehub.iot.webapp.drools;

import io.casehub.ras.drools.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

@ApplicationScoped
public class SustainedTemperatureRiseGanglion extends DroolsGanglion {

    private static final String DEFAULT_WINDOW_MINUTES = "30";
    private static final String DEFAULT_REQUIRED_COUNT = "5";
    private static final String DEFAULT_DELTA_CELSIUS = "3.0";

    @Inject
    public SustainedTemperatureRiseGanglion(DroolsSessionStore sessionStore) {
        this(sessionStore,
                Long.parseLong(System.getProperty(
                        "casehub.iot.ganglion.sustained-rise.window-minutes",
                        DEFAULT_WINDOW_MINUTES)),
                Integer.parseInt(System.getProperty(
                        "casehub.iot.ganglion.sustained-rise.required-count",
                        DEFAULT_REQUIRED_COUNT)),
                new BigDecimal(System.getProperty(
                        "casehub.iot.ganglion.sustained-rise.delta-celsius",
                        DEFAULT_DELTA_CELSIUS)));
    }

    public SustainedTemperatureRiseGanglion(
            DroolsSessionStore sessionStore,
            long windowMinutes,
            int requiredCount,
            BigDecimal deltaThreshold) {

        super(createConfig(windowMinutes, requiredCount, deltaThreshold),
                sessionStore,
                List.of(new TemperatureReadingExtractor()));
    }

    private static DroolsGanglionConfig createConfig(
            long windowMinutes, int requiredCount, BigDecimal deltaThreshold) {

        String drl = String.format("""
                package io.casehub.iot.webapp.drools;

                import io.casehub.ras.api.DetectionResult;
                import io.casehub.ras.api.DetectionSignal;
                import java.util.Map;
                import java.util.List;
                import java.math.BigDecimal;
                import io.casehub.iot.webapp.drools.TemperatureReading;

                rule "Sustained temperature rise"
                when
                    accumulate(
                        $r: TemperatureReading($ts: timestamp, $temp: celsius)
                            over window:time(%dm),
                        $readings: collectList($r),
                        $count: count($r);
                        $count >= %d
                    )
                    eval(isMonotonicIncreasing((List)$readings, new BigDecimal("%s")))
                then
                    List readingList = (List) $readings;
                    channels["results"].send(new DetectionResult(
                        "sustained-rise",
                        0.85,
                        DetectionSignal.DETECTED,
                        Map.of(
                            "readingCount", readingList.size(),
                            "firstTemp", ((TemperatureReading)readingList.get(0)).getCelsius(),
                            "lastTemp", ((TemperatureReading)readingList.get(readingList.size()-1)).getCelsius()
                        )
                    ));
                end

                function boolean isMonotonicIncreasing(List readings, BigDecimal minDelta) {
                    if (readings.size() < 2) return false;

                    for (int i = 1; i < readings.size(); i++) {
                        io.casehub.iot.webapp.drools.TemperatureReading prev =
                            (io.casehub.iot.webapp.drools.TemperatureReading) readings.get(i - 1);
                        io.casehub.iot.webapp.drools.TemperatureReading curr =
                            (io.casehub.iot.webapp.drools.TemperatureReading) readings.get(i);

                        BigDecimal delta = curr.getCelsius().subtract(prev.getCelsius());
                        if (delta.compareTo(minDelta) < 0) {
                            return false;
                        }
                    }
                    return true;
                }
                """, windowMinutes, requiredCount, deltaThreshold.toPlainString());

        return new DroolsGanglionConfig(
                "sustained-rise",
                Set.of("io.casehub.iot.state_change.sensor",
                        "io.casehub.iot.state_change.thermostat"),
                SessionMode.LONG_LIVED,
                ClockMode.PSEUDO,
                List.of(),
                List.of(drl),
                ResultCollectionStrategy.HIGHEST_CONFIDENCE);
    }
}
