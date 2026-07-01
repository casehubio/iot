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
public class TemperatureThresholdGanglion extends JavaSwitchGanglion {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BigDecimal upperThreshold;
    private final BigDecimal lowerThreshold;

    public TemperatureThresholdGanglion() {
        this(new BigDecimal("40.0"), new BigDecimal("2.0"));
    }

    public TemperatureThresholdGanglion(BigDecimal upperThreshold, BigDecimal lowerThreshold) {
        super("temperature-threshold", Set.of(
                "io.casehub.iot.state_change.sensor",
                "io.casehub.iot.state_change.thermostat"));
        this.upperThreshold = upperThreshold;
        this.lowerThreshold = lowerThreshold;
    }

    @Override
    protected DetectionResult evaluate(CloudEvent event, SituationContext context) {
        try {
            var data = event.getData();
            if (data == null) return noise();

            var root = MAPPER.readTree(data.toBytes());
            var after = root.path("after");
            if (after.isMissingNode()) return noise();

            var sensorType = after.path("sensorType").asText("");
            if (!"TEMPERATURE".equals(sensorType) && !isThermostatEvent(event)) return noise();

            BigDecimal tempValue = extractTemperatureCelsius(after);
            if (tempValue == null) return noise();

            if (tempValue.compareTo(upperThreshold) > 0) {
                double overshoot = tempValue.subtract(upperThreshold)
                        .divide(upperThreshold, 2, java.math.RoundingMode.HALF_UP)
                        .doubleValue();
                double confidence = Math.min(1.0, 0.6 + overshoot);
                return detected(confidence, Map.of(
                        "temperature", tempValue, "threshold", upperThreshold, "direction", "ABOVE"));
            }

            if (tempValue.compareTo(lowerThreshold) < 0) {
                double undershoot = lowerThreshold.subtract(tempValue)
                        .divide(lowerThreshold.abs().max(BigDecimal.ONE), 2, java.math.RoundingMode.HALF_UP)
                        .doubleValue();
                double confidence = Math.min(1.0, 0.6 + undershoot);
                return detected(confidence, Map.of(
                        "temperature", tempValue, "threshold", lowerThreshold, "direction", "BELOW"));
            }

            return noise();
        } catch (Exception e) {
            return noise();
        }
    }

    private boolean isThermostatEvent(CloudEvent event) {
        return "io.casehub.iot.state_change.thermostat".equals(event.getType());
    }

    private BigDecimal extractTemperatureCelsius(JsonNode after) {
        var numericValue = after.path("numericValue");
        var currentTemperature = after.path("currentTemperature");

        if (numericValue.isNumber()) {
            var unit = after.path("unit").asText("CELSIUS");
            var value = numericValue.decimalValue();
            return "FAHRENHEIT".equals(unit) ? fahrenheitToCelsius(value) : value;
        }

        if (currentTemperature.isObject()) {
            var value = currentTemperature.path("value").decimalValue();
            var unit = currentTemperature.path("unit").asText("CELSIUS");
            return "FAHRENHEIT".equals(unit) ? fahrenheitToCelsius(value) : value;
        }

        return null;
    }

    private static BigDecimal fahrenheitToCelsius(BigDecimal f) {
        return f.subtract(new BigDecimal("32"))
                .multiply(new BigDecimal("5"))
                .divide(new BigDecimal("9"), 2, java.math.RoundingMode.HALF_UP);
    }
}
