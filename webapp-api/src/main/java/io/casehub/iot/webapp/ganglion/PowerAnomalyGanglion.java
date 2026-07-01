package io.casehub.iot.webapp.ganglion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.ras.api.DetectionResult;
import io.casehub.ras.api.JavaSwitchGanglion;
import io.casehub.ras.api.SituationContext;
import io.cloudevents.CloudEvent;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class PowerAnomalyGanglion extends JavaSwitchGanglion {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BigDecimal threshold;

    public PowerAnomalyGanglion() {
        this(new BigDecimal("5000"));
    }

    public PowerAnomalyGanglion(BigDecimal threshold) {
        super("power-anomaly", Set.of("io.casehub.iot.state_change.power_sensor"));
        this.threshold = threshold;
    }

    @Override
    protected DetectionResult evaluate(CloudEvent event, SituationContext context) {
        try {
            var data = event.getData();
            if (data == null) return noise();

            var root = MAPPER.readTree(data.toBytes());
            var after = root.path("after");
            if (after.isMissingNode()) return noise();

            var power = after.path("power");
            if (power.isMissingNode()) return noise();

            var value = power.path("value");
            if (!value.isNumber()) return noise();

            BigDecimal powerValue = value.decimalValue();

            if (powerValue.compareTo(threshold) > 0) {
                double overshoot = powerValue.subtract(threshold)
                        .divide(threshold, 2, java.math.RoundingMode.HALF_UP)
                        .doubleValue();
                double confidence = Math.min(0.95, 0.7 + overshoot * 0.2);

                return detected(confidence, Map.of(
                        "power", powerValue,
                        "threshold", threshold,
                        "unit", power.path("unit").asText("WATTS")));
            }

            return noise();
        } catch (Exception e) {
            return noise();
        }
    }
}
