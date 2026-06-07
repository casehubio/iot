package io.casehub.iot.api;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

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
                .tenancyId("test-tenant")
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
                .tenancyId("test-tenant")
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
                .tenancyId("test-tenant")
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
                .tenancyId("test-tenant")
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
                .tenancyId("test-tenant")
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
                .tenancyId("test-tenant")
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
                    .tenancyId("test-tenant")
                    .present(true)
                    .build();
        });
    }

    @Test
    void powerSensorBuildsWithRequiredFields() {
        PowerSensor sensor = PowerSensor.builder()
                .deviceId(UUID.randomUUID().toString())
                .label("Main Circuit")
                .deviceClass(DeviceClass.POWER_SENSOR)
                .lastUpdated(TEST_INSTANT)
                .tenancyId("test-tenant")
                .power(new BigDecimal("1500.50"))
                .energy(new BigDecimal("3456.78"))
                .build();

        assertEquals(new BigDecimal("1500.50"), sensor.power());
        assertEquals(new BigDecimal("3456.78"), sensor.energy());
        assertEquals("power", PowerSensor.CAP_POWER);
        assertEquals("energy", PowerSensor.CAP_ENERGY);
    }

    @Test
    void powerSensorRequiresPowerAndEnergy() {
        assertThrows(NullPointerException.class, () -> {
            PowerSensor.builder()
                    .deviceId(UUID.randomUUID().toString())
                    .label("Main Circuit")
                    .deviceClass(DeviceClass.POWER_SENSOR)
                    .lastUpdated(TEST_INSTANT)
                    .tenancyId("test-tenant")
                    .power(new BigDecimal("1500.50"))
                    .build();
        });

        assertThrows(NullPointerException.class, () -> {
            PowerSensor.builder()
                    .deviceId(UUID.randomUUID().toString())
                    .label("Main Circuit")
                    .deviceClass(DeviceClass.POWER_SENSOR)
                    .lastUpdated(TEST_INSTANT)
                    .tenancyId("test-tenant")
                    .energy(new BigDecimal("3456.78"))
                    .build();
        });
    }
}
