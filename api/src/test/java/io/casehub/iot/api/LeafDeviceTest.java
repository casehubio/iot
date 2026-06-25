package io.casehub.iot.api;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class LeafDeviceTest {

    private static final Instant TEST_INSTANT = Instant.parse("2026-06-07T10:00:00Z");

    @Test
    void switchDeviceBuildsWithAllFields() {
        SwitchDevice device = SwitchDevice.builder()
                .deviceId(UUID.randomUUID().toString())
                .label("Living Room Switch")
                .deviceClass(DeviceClass.SWITCH)
                .lastUpdated(TEST_INSTANT)
                .tenancyId("test-tenant").providerId("test")
                .on(true)
                .build();

        assertTrue(device.isOn());
        assertEquals("Living Room Switch", device.label());
        assertEquals(DeviceClass.SWITCH, device.deviceClass());
    }

    @Test
    void switchDeviceCapabilityConstant() {
        assertEquals("isOn", SwitchDevice.CAP_ON);
    }

    @Test
    void fanDeviceBuildsWithAllFields() {
        FanDevice device = FanDevice.builder()
                .deviceId(UUID.randomUUID().toString())
                .label("Bedroom Fan")
                .deviceClass(DeviceClass.FAN)
                .lastUpdated(TEST_INSTANT)
                .tenancyId("test-tenant").providerId("test")
                .on(true)
                .speed(3)
                .build();

        assertTrue(device.isOn());
        assertTrue(device.speed().isPresent());
        assertEquals(3, device.speed().get());
        assertEquals("isOn", FanDevice.CAP_ON);
        assertEquals("speed", FanDevice.CAP_SPEED);
    }

    @Test
    void fanDeviceBuildsWithOptionalSpeedAbsent() {
        FanDevice device = FanDevice.builder()
                .deviceId(UUID.randomUUID().toString())
                .label("Bedroom Fan")
                .deviceClass(DeviceClass.FAN)
                .lastUpdated(TEST_INSTANT)
                .tenancyId("test-tenant").providerId("test")
                .on(false)
                .build();

        assertFalse(device.isOn());
        assertTrue(device.speed().isEmpty());
    }

    @Test
    void mediaPlayerDeviceBuildsWithAllFields() {
        MediaPlayerDevice device = MediaPlayerDevice.builder()
                .deviceId(UUID.randomUUID().toString())
                .label("Living Room TV")
                .deviceClass(DeviceClass.MEDIA_PLAYER)
                .lastUpdated(TEST_INSTANT)
                .tenancyId("test-tenant").providerId("test")
                .playing(true)
                .volume(75)
                .build();

        assertTrue(device.isPlaying());
        assertTrue(device.volume().isPresent());
        assertEquals(75, device.volume().get());
        assertEquals("isPlaying", MediaPlayerDevice.CAP_PLAYING);
        assertEquals("volume", MediaPlayerDevice.CAP_VOLUME);
    }

    @Test
    void mediaPlayerDeviceBuildsWithOptionalVolumeAbsent() {
        MediaPlayerDevice device = MediaPlayerDevice.builder()
                .deviceId(UUID.randomUUID().toString())
                .label("Living Room TV")
                .deviceClass(DeviceClass.MEDIA_PLAYER)
                .lastUpdated(TEST_INSTANT)
                .tenancyId("test-tenant").providerId("test")
                .playing(false)
                .build();

        assertFalse(device.isPlaying());
        assertTrue(device.volume().isEmpty());
    }

    @Test
    void presenceSensorBuildsWithRequiredFields() {
        PresenceSensor sensor = PresenceSensor.builder()
                .deviceId(UUID.randomUUID().toString())
                .label("Hallway Motion")
                .deviceClass(DeviceClass.PRESENCE_SENSOR)
                .lastUpdated(TEST_INSTANT)
                .tenancyId("test-tenant").providerId("test")
                .present(true)
                .lastSeen(TEST_INSTANT)
                .build();

        assertTrue(sensor.isPresent());
        assertEquals(TEST_INSTANT, sensor.lastSeen());
        assertEquals("isPresent", PresenceSensor.CAP_PRESENT);
        assertEquals("lastSeen", PresenceSensor.CAP_LAST_SEEN);
    }

    @Test
    void presenceSensorRequiresLastSeen() {
        assertThrows(NullPointerException.class, () -> {
            PresenceSensor.builder()
                    .deviceId(UUID.randomUUID().toString())
                    .label("Hallway Motion")
                    .deviceClass(DeviceClass.PRESENCE_SENSOR)
                    .lastUpdated(TEST_INSTANT)
                    .tenancyId("test-tenant").providerId("test")
                    .present(true)
                    .build();
        });
    }

    @Test
    void powerSensorBuildsWithBothFields() {
        PowerSensor sensor = PowerSensor.builder()
                .deviceId(UUID.randomUUID().toString())
                .label("Main Circuit")
                .deviceClass(DeviceClass.POWER_SENSOR)
                .lastUpdated(TEST_INSTANT)
                .tenancyId("test-tenant").providerId("test")
                .power(new BigDecimal("1500.50"))
                .energy(new BigDecimal("3456.78"))
                .build();

        assertThat(sensor.power()).hasValue(new BigDecimal("1500.50"));
        assertThat(sensor.energy()).hasValue(new BigDecimal("3456.78"));
        assertEquals("power", PowerSensor.CAP_POWER);
        assertEquals("energy", PowerSensor.CAP_ENERGY);
    }

    @Test
    void powerSensorBuildsWithPowerOnly() {
        PowerSensor sensor = PowerSensor.builder()
                .deviceId(UUID.randomUUID().toString())
                .label("Solar Panel")
                .deviceClass(DeviceClass.POWER_SENSOR)
                .lastUpdated(TEST_INSTANT)
                .tenancyId("test-tenant").providerId("test")
                .power(new BigDecimal("3200"))
                .build();

        assertThat(sensor.power()).hasValue(new BigDecimal("3200"));
        assertThat(sensor.energy()).isEmpty();
    }

    @Test
    void cameraDeviceBuildsWithAllFields() {
        CameraDevice device = CameraDevice.builder()
                .deviceId(UUID.randomUUID().toString())
                .label("Front Door Camera")
                .deviceClass(DeviceClass.CAMERA)
                .lastUpdated(TEST_INSTANT)
                .tenancyId("test-tenant").providerId("test")
                .streaming(true)
                .build();

        assertTrue(device.isStreaming());
        assertEquals("Front Door Camera", device.label());
        assertEquals(DeviceClass.CAMERA, device.deviceClass());
        assertEquals("isStreaming", CameraDevice.CAP_STREAMING);
    }

    @Test
    void cameraDeviceDefaultsStreamingToFalse() {
        CameraDevice device = CameraDevice.builder()
                .deviceId(UUID.randomUUID().toString())
                .label("Backyard Camera")
                .deviceClass(DeviceClass.CAMERA)
                .lastUpdated(TEST_INSTANT)
                .tenancyId("test-tenant").providerId("test")
                .build();

        assertFalse(device.isStreaming());
    }

    @Test
    void cameraDeviceCapabilitiesContainStreamingAndAvailable() {
        CameraDevice device = CameraDevice.builder()
                .deviceId(UUID.randomUUID().toString())
                .label("Camera")
                .deviceClass(DeviceClass.CAMERA)
                .lastUpdated(TEST_INSTANT)
                .tenancyId("test-tenant").providerId("test")
                .streaming(true)
                .build();

        assertThat(device.capabilities()).containsEntry("available", true);
        assertThat(device.capabilities()).containsEntry("isStreaming", true);
        assertThat(device.capabilities()).hasSize(2);
    }

    @Test
    void powerSensorBuildsWithEnergyOnly() {
        PowerSensor sensor = PowerSensor.builder()
                .deviceId(UUID.randomUUID().toString())
                .label("Grid Meter")
                .deviceClass(DeviceClass.POWER_SENSOR)
                .lastUpdated(TEST_INSTANT)
                .tenancyId("test-tenant").providerId("test")
                .energy(new BigDecimal("15.2"))
                .build();

        assertThat(sensor.power()).isEmpty();
        assertThat(sensor.energy()).hasValue(new BigDecimal("15.2"));
    }
}
