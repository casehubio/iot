package io.casehub.iot.webapp.app.simulation;

import io.casehub.iot.api.DeviceClass;
import io.casehub.iot.api.LightDevice;
import io.casehub.iot.api.PresenceSensor;
import io.casehub.iot.api.SensorDevice;
import io.casehub.iot.api.StateChangeEvent;
import io.casehub.iot.api.ThermostatDevice;
import io.casehub.iot.api.ThermostatMode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimulationPayloadConverterTest {

    private final SimulationPayloadConverter converter = new SimulationPayloadConverter();

    @Test
    void convertsPresenceSensorPayload() {
        Map<String, Object> payload = Map.of(
                "deviceId", "presence-front-1",
                "deviceClass", "PRESENCE_SENSOR",
                "present", true);

        StateChangeEvent event = converter.convert(payload);

        assertThat(event.after()).isInstanceOf(PresenceSensor.class);
        assertThat(event.after().deviceId()).isEqualTo("presence-front-1");
        assertThat(event.after().deviceClass()).isEqualTo(DeviceClass.PRESENCE_SENSOR);
        assertThat(((PresenceSensor) event.after()).isPresent()).isTrue();
        assertThat(event.providerId()).isEqualTo("simulation");
        assertThat(event.before()).isNull();
        assertThat(event.changedCapabilities()).isNotEmpty();
    }

    @Test
    void convertsLightPayload() {
        Map<String, Object> payload = Map.of(
                "deviceId", "light-living-1",
                "deviceClass", "LIGHT",
                "on", true);

        StateChangeEvent event = converter.convert(payload);

        assertThat(event.after()).isInstanceOf(LightDevice.class);
        LightDevice light = (LightDevice) event.after();
        assertThat(light.deviceId()).isEqualTo("light-living-1");
        assertThat(light.isOn()).isTrue();
    }

    @Test
    void convertsThermostatPayload() {
        Map<String, Object> payload = Map.of(
                "deviceId", "thermostat-living-1",
                "deviceClass", "THERMOSTAT",
                "targetTemperature", Map.of("value", 22, "unit", "CELSIUS"),
                "mode", "HEAT");

        StateChangeEvent event = converter.convert(payload);

        assertThat(event.after()).isInstanceOf(ThermostatDevice.class);
        ThermostatDevice thermostat = (ThermostatDevice) event.after();
        assertThat(thermostat.targetTemperature().value())
                .isEqualByComparingTo(BigDecimal.valueOf(22));
        assertThat(thermostat.mode()).isEqualTo(ThermostatMode.HEAT);
    }

    @Test
    void convertsThermostatWithCurrentTemperatureOnly() {
        Map<String, Object> payload = Map.of(
                "deviceId", "thermostat-living-1",
                "deviceClass", "THERMOSTAT",
                "currentTemperature", Map.of("value", 19.5, "unit", "CELSIUS"),
                "mode", "OFF");

        StateChangeEvent event = converter.convert(payload);

        ThermostatDevice thermostat = (ThermostatDevice) event.after();
        assertThat(thermostat.currentTemperature().value())
                .isEqualByComparingTo(BigDecimal.valueOf(19.5));
        assertThat(thermostat.targetTemperature()).isNotNull();
    }

    @Test
    void convertsSensorPayload() {
        Map<String, Object> payload = Map.of(
                "deviceId", "sensor-smoke-1",
                "deviceClass", "SENSOR",
                "sensorType", "GENERIC",
                "numericValue", 1);

        StateChangeEvent event = converter.convert(payload);

        assertThat(event.after()).isInstanceOf(SensorDevice.class);
        SensorDevice sensor = (SensorDevice) event.after();
        assertThat(sensor.numericValue()).isPresent();
        assertThat(sensor.numericValue().get()).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void rejectsUnsupportedDeviceClass() {
        Map<String, Object> payload = Map.of(
                "deviceId", "camera-1",
                "deviceClass", "CAMERA");

        assertThatThrownBy(() -> converter.convert(payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported device class");
    }

    @Test
    void useDefaultTenancyWhenNotSpecified() {
        Map<String, Object> payload = Map.of(
                "deviceId", "light-1",
                "deviceClass", "LIGHT",
                "on", false);

        StateChangeEvent event = converter.convert(payload);

        assertThat(event.after().tenancyId()).isEqualTo("default-tenant");
    }
}
