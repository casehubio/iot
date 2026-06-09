package io.casehub.iot.api;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests {@code toBuilder()} on non-extensible (leaf) device types.
 * Extensible types (LightDevice, ThermostatDevice, LockDevice, CoverDevice)
 * do not expose toBuilder() at the base level — supplements provide their own.
 */
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
        assertThat(copy.power()).hasValue(new BigDecimal("100"));
        assertThat(copy.energy()).hasValue(new BigDecimal("50"));
        assertThat(copy).isInstanceOf(PowerSensor.class);
    }

    @Test
    void powerSensorToBuilderPreservesNullOptionals() {
        var original = PowerSensor.builder()
            .deviceId("ps1").deviceClass(DeviceClass.POWER_SENSOR).label("Power")
            .available(true).lastUpdated(NOW).tenancyId("t1")
            .power(new BigDecimal("100")).build();
        var copy = original.toBuilder().build();
        assertThat(copy.power()).hasValue(new BigDecimal("100"));
        assertThat(copy.energy()).isEmpty();
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
