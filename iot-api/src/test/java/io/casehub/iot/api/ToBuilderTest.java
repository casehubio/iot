package io.casehub.iot.api;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

class ToBuilderTest {

    static final Instant NOW = Instant.parse("2026-06-07T10:00:00Z");

    @Test
    void switchDeviceToBuilderRoundTrip() {
        var original = SwitchDevice.builder()
            .deviceId("sw1").deviceClass(DeviceClass.SWITCH).label("Switch")
            .available(true).lastUpdated(NOW).tenancyId("t1").on(true).build();
        var copy = original.toBuilder().build();
        assertThat(copy.deviceId()).isEqualTo("sw1");
        assertThat(copy.deviceClass()).isEqualTo(DeviceClass.SWITCH);
        assertThat(copy.label()).isEqualTo("Switch");
        assertThat(copy.available()).isTrue();
        assertThat(copy.lastUpdated()).isEqualTo(NOW);
        assertThat(copy.tenancyId()).isEqualTo("t1");
        assertThat(copy.isOn()).isTrue();
    }

    @Test
    void switchDeviceToBuilderModifyOn() {
        var original = SwitchDevice.builder()
            .deviceId("sw1").deviceClass(DeviceClass.SWITCH).label("Switch")
            .available(true).lastUpdated(NOW).tenancyId("t1").on(true).build();
        SwitchDevice modified = original.toBuilder().on(false).build();
        assertThat(modified.isOn()).isFalse();
        assertThat(modified.deviceId()).isEqualTo("sw1");
        assertThat(modified).isInstanceOf(SwitchDevice.class);
    }

    @Test
    void lightDeviceToBuilderRoundTrip() {
        var original = LightDevice.builder()
            .deviceId("l1").deviceClass(DeviceClass.LIGHT).label("Light")
            .available(true).lastUpdated(NOW).tenancyId("t1")
            .on(true).brightness(200).colorTemp(4000).build();
        var copy = original.toBuilder().build();
        assertThat(copy.deviceId()).isEqualTo("l1");
        assertThat(copy.isOn()).isTrue();
        assertThat(copy.brightness()).hasValue(200);
        assertThat(copy.colorTemp()).hasValue(4000);
        assertThat(copy).isInstanceOf(LightDevice.class);
    }

    @Test
    void lightDeviceToBuilderModifyBrightness() {
        var original = LightDevice.builder()
            .deviceId("l1").deviceClass(DeviceClass.LIGHT).label("Light")
            .available(true).lastUpdated(NOW).tenancyId("t1")
            .on(true).brightness(200).build();
        LightDevice modified = original.toBuilder().brightness(100).build();
        assertThat(modified.brightness()).hasValue(100);
        assertThat(modified.isOn()).isTrue();
        assertThat(modified).isInstanceOf(LightDevice.class);
    }

    @Test
    void lightDeviceToBuilderPreservesNullOptionals() {
        var original = LightDevice.builder()
            .deviceId("l1").deviceClass(DeviceClass.LIGHT).label("Light")
            .available(true).lastUpdated(NOW).tenancyId("t1").on(false).build();
        var copy = original.toBuilder().build();
        assertThat(copy.brightness()).isEmpty();
        assertThat(copy.colorTemp()).isEmpty();
    }

    @Test
    void thermostatDeviceToBuilderRoundTrip() {
        var current = new Temperature(new BigDecimal("21"), Temperature.TemperatureUnit.CELSIUS);
        var target = new Temperature(new BigDecimal("22"), Temperature.TemperatureUnit.CELSIUS);
        var original = ThermostatDevice.builder()
            .deviceId("th1").deviceClass(DeviceClass.THERMOSTAT).label("Thermostat")
            .available(true).lastUpdated(NOW).tenancyId("t1")
            .currentTemperature(current).targetTemperature(target).mode(ThermostatMode.HEAT).build();
        var copy = original.toBuilder().build();
        assertThat(copy.currentTemperature()).isEqualTo(current);
        assertThat(copy.targetTemperature()).isEqualTo(target);
        assertThat(copy.mode()).isEqualTo(ThermostatMode.HEAT);
        assertThat(copy).isInstanceOf(ThermostatDevice.class);
    }

    @Test
    void thermostatDeviceToBuilderModifyTarget() {
        var current = new Temperature(new BigDecimal("21"), Temperature.TemperatureUnit.CELSIUS);
        var target = new Temperature(new BigDecimal("22"), Temperature.TemperatureUnit.CELSIUS);
        var newTarget = new Temperature(new BigDecimal("23"), Temperature.TemperatureUnit.CELSIUS);
        var original = ThermostatDevice.builder()
            .deviceId("th1").deviceClass(DeviceClass.THERMOSTAT).label("Thermostat")
            .available(true).lastUpdated(NOW).tenancyId("t1")
            .currentTemperature(current).targetTemperature(target).mode(ThermostatMode.HEAT).build();
        ThermostatDevice modified = original.toBuilder().targetTemperature(newTarget).build();
        assertThat(modified.targetTemperature()).isEqualTo(newTarget);
        assertThat(modified.currentTemperature()).isEqualTo(current);
    }

    @Test
    void sensorDeviceToBuilderPreservesUnit() {
        var original = SensorDevice.builder()
            .deviceId("s1").deviceClass(DeviceClass.SENSOR).label("Sensor")
            .available(true).lastUpdated(NOW).tenancyId("t1")
            .sensorType(SensorType.TEMPERATURE)
            .numericValue(new BigDecimal("21.5")).unit("C").build();
        var copy = original.toBuilder().build();
        assertThat(copy.unit()).hasValue("C");
        assertThat(copy.numericValue()).hasValue(new BigDecimal("21.5"));
        assertThat(copy.sensorType()).isEqualTo(SensorType.TEMPERATURE);
    }

    @Test
    void sensorDeviceToBuilderModifyNumericValue() {
        var original = SensorDevice.builder()
            .deviceId("s1").deviceClass(DeviceClass.SENSOR).label("Sensor")
            .available(true).lastUpdated(NOW).tenancyId("t1")
            .sensorType(SensorType.TEMPERATURE)
            .numericValue(new BigDecimal("21")).unit("C").build();
        SensorDevice modified = original.toBuilder().numericValue(new BigDecimal("22")).build();
        assertThat(modified.numericValue()).hasValue(new BigDecimal("22"));
        assertThat(modified.unit()).hasValue("C");
    }

    @Test
    void presenceSensorToBuilderRoundTrip() {
        var original = PresenceSensor.builder()
            .deviceId("p1").deviceClass(DeviceClass.PRESENCE_SENSOR).label("Presence")
            .available(true).lastUpdated(NOW).tenancyId("t1")
            .present(false).lastSeen(NOW).build();
        var copy = original.toBuilder().build();
        assertThat(copy.isPresent()).isFalse();
        assertThat(copy.lastSeen()).isEqualTo(NOW);
        assertThat(copy).isInstanceOf(PresenceSensor.class);
    }

    @Test
    void powerSensorToBuilderRoundTrip() {
        var original = PowerSensor.builder()
            .deviceId("ps1").deviceClass(DeviceClass.POWER_SENSOR).label("Power")
            .available(true).lastUpdated(NOW).tenancyId("t1")
            .power(new BigDecimal("100")).energy(new BigDecimal("50")).build();
        var copy = original.toBuilder().build();
        assertThat(copy.power()).isEqualByComparingTo(new BigDecimal("100"));
        assertThat(copy.energy()).isEqualByComparingTo(new BigDecimal("50"));
        assertThat(copy).isInstanceOf(PowerSensor.class);
    }

    @Test
    void lockDeviceToBuilderModifyLocked() {
        var original = LockDevice.builder()
            .deviceId("lk1").deviceClass(DeviceClass.LOCK).label("Lock")
            .available(true).lastUpdated(NOW).tenancyId("t1").locked(true).build();
        LockDevice unlocked = original.toBuilder().locked(false).build();
        assertThat(unlocked.isLocked()).isFalse();
        assertThat(unlocked.deviceId()).isEqualTo("lk1");
        assertThat(unlocked).isInstanceOf(LockDevice.class);
    }

    @Test
    void coverDeviceToBuilderModifyPosition() {
        var original = CoverDevice.builder()
            .deviceId("cv1").deviceClass(DeviceClass.COVER).label("Cover")
            .available(true).lastUpdated(NOW).tenancyId("t1")
            .position(0).moving(false).build();
        CoverDevice opened = original.toBuilder().position(100).build();
        assertThat(opened.position()).isEqualTo(100);
        assertThat(opened.isMoving()).isFalse();
        assertThat(opened).isInstanceOf(CoverDevice.class);
    }

    @Test
    void mediaPlayerDeviceToBuilderModifyVolume() {
        var original = MediaPlayerDevice.builder()
            .deviceId("mp1").deviceClass(DeviceClass.MEDIA_PLAYER).label("Player")
            .available(true).lastUpdated(NOW).tenancyId("t1")
            .playing(true).volume(80).build();
        MediaPlayerDevice modified = original.toBuilder().volume(60).build();
        assertThat(modified.volume()).hasValue(60);
        assertThat(modified.isPlaying()).isTrue();
        assertThat(modified).isInstanceOf(MediaPlayerDevice.class);
    }

    @Test
    void fanDeviceToBuilderModifySpeed() {
        var original = FanDevice.builder()
            .deviceId("f1").deviceClass(DeviceClass.FAN).label("Fan")
            .available(true).lastUpdated(NOW).tenancyId("t1").on(true).speed(3).build();
        FanDevice modified = original.toBuilder().speed(5).build();
        assertThat(modified.speed()).hasValue(5);
        assertThat(modified.isOn()).isTrue();
        assertThat(modified).isInstanceOf(FanDevice.class);
    }
}
