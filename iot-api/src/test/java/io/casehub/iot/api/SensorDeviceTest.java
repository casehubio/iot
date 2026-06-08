package io.casehub.iot.api;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SensorDeviceTest {

    private static final Instant TEST_INSTANT = Instant.parse("2026-06-07T10:00:00Z");

    @Test
    void sensorDeviceBuildsWithNumericValue() {
        SensorDevice sensor = SensorDevice.builder()
                .deviceId(UUID.randomUUID().toString())
                .label("Temperature Sensor")
                .deviceClass(DeviceClass.SENSOR)
                .lastUpdated(TEST_INSTANT)
                .tenancyId("test-tenant")
                .sensorType(SensorType.TEMPERATURE)
                .numericValue(new BigDecimal("22.5"))
                .unit("C")
                .build();

        assertEquals(SensorType.TEMPERATURE, sensor.sensorType());
        assertTrue(sensor.numericValue().isPresent());
        assertEquals(new BigDecimal("22.5"), sensor.numericValue().get());
        assertTrue(sensor.unit().isPresent());
        assertEquals("C", sensor.unit().get());
        assertTrue(sensor.binaryValue().isEmpty());
    }

    @Test
    void sensorDeviceBuildsWithBinaryValue() {
        SensorDevice sensor = SensorDevice.builder()
                .deviceId(UUID.randomUUID().toString())
                .label("Door Sensor")
                .deviceClass(DeviceClass.SENSOR)
                .lastUpdated(TEST_INSTANT)
                .tenancyId("test-tenant")
                .sensorType(SensorType.DOOR_WINDOW)
                .binaryValue(true)
                .build();

        assertEquals(SensorType.DOOR_WINDOW, sensor.sensorType());
        assertTrue(sensor.binaryValue().isPresent());
        assertTrue(sensor.binaryValue().get());
        assertTrue(sensor.numericValue().isEmpty());
        assertTrue(sensor.unit().isEmpty());
    }

    @Test
    void sensorDeviceRequiresSensorType() {
        assertThrows(NullPointerException.class, () -> {
            SensorDevice.builder()
                    .deviceId(UUID.randomUUID().toString())
                    .label("Temperature Sensor")
                    .deviceClass(DeviceClass.SENSOR)
                    .lastUpdated(TEST_INSTANT)
                    .tenancyId("test-tenant")
                    .numericValue(new BigDecimal("22.5"))
                    .build();
        });
    }

    @Test
    void sensorDeviceCapabilityConstants() {
        assertEquals("numericValue", SensorDevice.CAP_NUMERIC_VALUE);
        assertEquals("binaryValue", SensorDevice.CAP_BINARY_VALUE);
    }
}
