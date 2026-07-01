package io.casehub.iot.webapp.drools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.ras.drools.DroolsObjectExtractor;
import io.cloudevents.CloudEvent;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

public class TemperatureReadingExtractor implements DroolsObjectExtractor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public Set<String> handledEventTypes() {
        return Set.of(
                "io.casehub.iot.state_change.sensor",
                "io.casehub.iot.state_change.thermostat");
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

            BigDecimal celsius = extractTemperatureCelsius(after, event.getType());
            if (celsius == null) return List.of();

            var eventTime = event.getTime();
            if (eventTime == null) return List.of();

            long timestamp = eventTime.toInstant().toEpochMilli();

            return List.of(new TemperatureReading(deviceId, celsius, timestamp));
        } catch (Exception e) {
            return List.of();
        }
    }

    private BigDecimal extractTemperatureCelsius(JsonNode after, String eventType) {
        if ("io.casehub.iot.state_change.sensor".equals(eventType)) {
            String sensorType = after.path("sensorType").asText("");
            if (!"TEMPERATURE".equals(sensorType)) return null;

            var numericValue = after.path("numericValue");
            if (!numericValue.isNumber()) return null;

            String unit = after.path("unit").asText("CELSIUS");
            BigDecimal value = numericValue.decimalValue();

            return "FAHRENHEIT".equals(unit) ? fahrenheitToCelsius(value) : value;
        }

        if ("io.casehub.iot.state_change.thermostat".equals(eventType)) {
            var currentTemp = after.path("currentTemperature");
            if (currentTemp.isMissingNode() || !currentTemp.isObject()) return null;

            var value = currentTemp.path("value");
            if (!value.isNumber()) return null;

            String unit = currentTemp.path("unit").asText("CELSIUS");
            BigDecimal tempValue = value.decimalValue();

            return "FAHRENHEIT".equals(unit) ? fahrenheitToCelsius(tempValue) : tempValue;
        }

        return null;
    }

    private static BigDecimal fahrenheitToCelsius(BigDecimal f) {
        return f.subtract(new BigDecimal("32"))
                .multiply(new BigDecimal("5"))
                .divide(new BigDecimal("9"), 2, java.math.RoundingMode.HALF_UP);
    }
}
