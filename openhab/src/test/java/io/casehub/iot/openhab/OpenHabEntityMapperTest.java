package io.casehub.iot.openhab;

import io.casehub.iot.api.*;
import io.casehub.iot.openhab.internal.OpenHabItemDto;
import io.casehub.iot.openhab.internal.OpenHabStateDescriptionDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OpenHabEntityMapperTest {

    private static final Instant NOW = Instant.parse("2026-06-10T12:00:00Z");
    private final OpenHabEntityMapper mapper = new OpenHabEntityMapper("test-tenant");

    // ---- helper methods ----

    private OpenHabItemDto equipment(String name, String label, List<String> tags, OpenHabItemDto... members) {
        return new OpenHabItemDto("Group", name, label, "NULL", tags, List.of(members), null);
    }

    private OpenHabItemDto member(String type, String name, String state, List<String> tags) {
        return new OpenHabItemDto(type, name, name, state, tags, null, null);
    }

    private OpenHabItemDto memberWithDesc(String type, String name, String state, List<String> tags, String pattern) {
        return new OpenHabItemDto(type, name, name, state, tags, null, new OpenHabStateDescriptionDto(pattern));
    }

    // ---- 1. HVAC Equipment → ThermostatDevice ----

    @Test
    void hvacEquipmentMapsToThermostat() {
        var eq = equipment("ThermostatLiving", "Living Room Thermostat",
                List.of("Equipment", "HVAC"),
                member("Number", "ThermostatLiving_CurrentTemp", "21.5",
                        List.of("Point", "Measurement", "Temperature")),
                member("Number", "ThermostatLiving_TargetTemp", "22.0",
                        List.of("Point", "Setpoint", "Temperature")),
                member("String", "ThermostatLiving_Mode", "heat",
                        List.of()));

        var result = mapper.mapEquipment(eq, NOW);

        assertThat(result).isInstanceOf(ThermostatDevice.class);
        var therm = (ThermostatDevice) result;
        assertThat(therm.deviceClass()).isEqualTo(DeviceClass.THERMOSTAT);
        assertThat(therm.currentTemperature().value()).isEqualByComparingTo(new BigDecimal("21.5"));
        assertThat(therm.currentTemperature().unit()).isEqualTo(Temperature.TemperatureUnit.CELSIUS);
        assertThat(therm.targetTemperature().value()).isEqualByComparingTo(new BigDecimal("22.0"));
        assertThat(therm.mode()).isEqualTo(ThermostatMode.HEAT);
    }

    // ---- 2. HVAC with heating demand → OpenHabThermostat ----

    @Test
    void hvacWithHeatingDemandMapsToOpenHabThermostat() {
        var eq = equipment("ThermostatHall", "Hall Thermostat",
                List.of("Equipment", "HVAC"),
                member("Number", "ThermostatHall_CurrentTemp", "19.0",
                        List.of("Point", "Measurement", "Temperature")),
                member("Number", "ThermostatHall_TargetTemp", "21.0",
                        List.of("Point", "Setpoint", "Temperature")),
                member("Number", "ThermostatHall_HeatingDemand", "75",
                        List.of("Point", "Measurement", "Level")),
                member("String", "ThermostatHall_Mode", "heat",
                        List.of()));

        var result = mapper.mapEquipment(eq, NOW);

        assertThat(result).isInstanceOf(OpenHabThermostat.class);
        var therm = (OpenHabThermostat) result;
        assertThat(therm.heatingDemand()).hasValue(new BigDecimal("75"));
        assertThat(therm.coolingDemand()).isEmpty();
    }

    // ---- 3. Lightbulb → LightDevice ----

    @Test
    void lightbulbMapsToLightDevice() {
        var eq = equipment("LightKitchen", "Kitchen Light",
                List.of("Equipment", "Lightbulb"),
                member("Switch", "LightKitchen_Switch", "ON",
                        List.of("Point", "Control", "Switch")));

        var result = mapper.mapEquipment(eq, NOW);

        assertThat(result).isInstanceOf(LightDevice.class);
        var light = (LightDevice) result;
        assertThat(light.deviceClass()).isEqualTo(DeviceClass.LIGHT);
        assertThat(light.isOn()).isTrue();
    }

    // ---- 4. Color item → OpenHabLight with HSB ----

    @Test
    void colorItemMapsToOpenHabLight() {
        var eq = equipment("LightColor", "Color Light",
                List.of("Equipment", "Lightbulb"),
                member("Color", "LightColor_Color", "240,100,50",
                        List.of("Point", "Control", "Switch")));

        var result = mapper.mapEquipment(eq, NOW);

        assertThat(result).isInstanceOf(OpenHabLight.class);
        var light = (OpenHabLight) result;
        assertThat(light.hsb()).isPresent();
        assertThat(light.hsb().get().hue()).isEqualByComparingTo(new BigDecimal("240"));
        assertThat(light.hsb().get().saturation()).isEqualByComparingTo(new BigDecimal("100"));
        assertThat(light.hsb().get().brightness()).isEqualByComparingTo(new BigDecimal("50"));
        assertThat(light.isOn()).isTrue();
    }

    // ---- 5. Rollershutter position is inverted ----

    @Test
    void rollershutterPositionIsInverted() {
        var eq = equipment("BlindsBedroom", "Bedroom Blinds",
                List.of("Equipment", "Blinds"),
                member("Rollershutter", "BlindsBedroom_Position", "30",
                        List.of("Point", "Status", "OpenState")));

        var result = mapper.mapEquipment(eq, NOW);

        assertThat(result).isInstanceOf(OpenHabRollershutter.class);
        var cover = (OpenHabRollershutter) result;
        assertThat(cover.deviceClass()).isEqualTo(DeviceClass.COVER);
        // OH 30% → CaseHub 70% (inverted)
        assertThat(cover.position()).hasValue(70);
    }

    // ---- 6. Contact OpenState maps correctly ----

    @Test
    void contactOpenStateMapsCorrectly() {
        var eqOpen = equipment("BlindsFront", "Front Blinds",
                List.of("Equipment", "Blinds"),
                member("Contact", "BlindsFront_Contact", "OPEN",
                        List.of("Point", "Status", "OpenState")));

        var result = mapper.mapEquipment(eqOpen, NOW);

        assertThat(result).isInstanceOf(CoverDevice.class);
        var cover = (CoverDevice) result;
        assertThat(cover.position()).hasValue(100);

        // CLOSED → 0
        var eqClosed = equipment("BlindsFront2", "Front Blinds 2",
                List.of("Equipment", "Blinds"),
                member("Contact", "BlindsFront2_Contact", "CLOSED",
                        List.of("Point", "Status", "OpenState")));

        var result2 = mapper.mapEquipment(eqClosed, NOW);
        var cover2 = (CoverDevice) result2;
        assertThat(cover2.position()).hasValue(0);
    }

    // ---- 7. Switch equipment → SwitchDevice ----

    @Test
    void switchEquipmentMapsToSwitch() {
        var eq = equipment("OutletGarage", "Garage Outlet",
                List.of("Equipment", "PowerOutlet"),
                member("Switch", "OutletGarage_Switch", "ON",
                        List.of("Point", "Control", "Switch")));

        var result = mapper.mapEquipment(eq, NOW);

        assertThat(result).isInstanceOf(SwitchDevice.class);
        var sw = (SwitchDevice) result;
        assertThat(sw.deviceClass()).isEqualTo(DeviceClass.SWITCH);
        assertThat(sw.isOn()).isTrue();
    }

    // ---- 8. Lock equipment → LockDevice ----

    @Test
    void lockEquipmentMapsToLock() {
        var eq = equipment("LockFront", "Front Door Lock",
                List.of("Equipment", "Lock"),
                member("Switch", "LockFront_Switch", "ON",
                        List.of("Point", "Control", "Switch")));

        var result = mapper.mapEquipment(eq, NOW);

        assertThat(result).isInstanceOf(LockDevice.class);
        var lock = (LockDevice) result;
        assertThat(lock.deviceClass()).isEqualTo(DeviceClass.LOCK);
        assertThat(lock.isLocked()).isTrue();
    }

    // ---- 9. Fan equipment → FanDevice ----

    @Test
    void fanEquipmentMapsToFan() {
        var eq = equipment("FanBedroom", "Bedroom Fan",
                List.of("Equipment", "Fan"),
                member("Switch", "FanBedroom_Switch", "ON",
                        List.of("Point", "Control", "Switch")));

        var result = mapper.mapEquipment(eq, NOW);

        assertThat(result).isInstanceOf(FanDevice.class);
        var fan = (FanDevice) result;
        assertThat(fan.deviceClass()).isEqualTo(DeviceClass.FAN);
        assertThat(fan.isOn()).isTrue();
    }

    // ---- 10. Media player → MediaPlayerDevice ----

    @Test
    void mediaPlayerMapsToMediaPlayer() {
        var eq = equipment("TVLiving", "Living Room TV",
                List.of("Equipment", "Television"),
                member("Dimmer", "TVLiving_Volume", "65",
                        List.of("Point", "Control", "SoundVolume")));

        var result = mapper.mapEquipment(eq, NOW);

        assertThat(result).isInstanceOf(MediaPlayerDevice.class);
        var mp = (MediaPlayerDevice) result;
        assertThat(mp.deviceClass()).isEqualTo(DeviceClass.MEDIA_PLAYER);
        assertThat(mp.volume()).hasValue(65);
    }

    // ---- 11. Sensor with humidity ----

    @Test
    void sensorWithHumidityMapsCorrectly() {
        var eq = equipment("SensorBathroom", "Bathroom Sensor",
                List.of("Equipment", "Sensor"),
                member("Number", "SensorBathroom_Humidity", "68.5",
                        List.of("Point", "Measurement", "Humidity")));

        var result = mapper.mapEquipment(eq, NOW);

        assertThat(result).isInstanceOf(SensorDevice.class);
        var sensor = (SensorDevice) result;
        assertThat(sensor.deviceClass()).isEqualTo(DeviceClass.SENSOR);
        assertThat(sensor.sensorType()).isEqualTo(SensorType.HUMIDITY);
        assertThat(sensor.numericValue()).hasValue(new BigDecimal("68.5"));
    }

    // ---- 12. MotionDetector → SENSOR with MOTION ----

    @Test
    void motionDetectorMapsToSensorMotion() {
        var eq = equipment("MotionHall", "Hallway Motion",
                List.of("Equipment", "MotionDetector"),
                member("Switch", "MotionHall_Switch", "ON",
                        List.of("Point", "Control", "Switch")));

        var result = mapper.mapEquipment(eq, NOW);

        assertThat(result).isInstanceOf(SensorDevice.class);
        var sensor = (SensorDevice) result;
        assertThat(sensor.deviceClass()).isEqualTo(DeviceClass.SENSOR);
        assertThat(sensor.sensorType()).isEqualTo(SensorType.MOTION);
    }

    // ---- 13. Battery → SENSOR with GENERIC and "%" ----

    @Test
    void batteryMapsToSensorGeneric() {
        var eq = equipment("BatteryLock", "Lock Battery",
                List.of("Equipment", "Battery"),
                member("Number", "BatteryLock_Level", "85",
                        List.of("Point", "Measurement", "Level")));

        var result = mapper.mapEquipment(eq, NOW);

        assertThat(result).isInstanceOf(SensorDevice.class);
        var sensor = (SensorDevice) result;
        assertThat(sensor.deviceClass()).isEqualTo(DeviceClass.SENSOR);
        assertThat(sensor.sensorType()).isEqualTo(SensorType.GENERIC);
        assertThat(sensor.unit()).hasValue("%");
    }

    // ---- 14. SmokeDetector → SENSOR with GENERIC (not CO) ----

    @Test
    void smokeDetectorMapsToSensorGeneric() {
        var eq = equipment("SmokeKitchen", "Kitchen Smoke Detector",
                List.of("Equipment", "SmokeDetector"),
                member("Switch", "SmokeKitchen_Alarm", "OFF",
                        List.of("Point", "Status")));

        var result = mapper.mapEquipment(eq, NOW);

        assertThat(result).isInstanceOf(SensorDevice.class);
        var sensor = (SensorDevice) result;
        assertThat(sensor.deviceClass()).isEqualTo(DeviceClass.SENSOR);
        assertThat(sensor.sensorType()).isEqualTo(SensorType.GENERIC);
    }

    // ---- 15. AirConditioner → THERMOSTAT ----

    @Test
    void airConditionerMapsToThermostat() {
        var eq = equipment("ACBedroom", "Bedroom AC",
                List.of("Equipment", "AirConditioner"),
                member("Number", "ACBedroom_CurrentTemp", "25.0",
                        List.of("Point", "Measurement", "Temperature")),
                member("Number", "ACBedroom_TargetTemp", "22.0",
                        List.of("Point", "Setpoint", "Temperature")),
                member("String", "ACBedroom_Mode", "cool",
                        List.of()));

        var result = mapper.mapEquipment(eq, NOW);

        assertThat(result).isInstanceOf(ThermostatDevice.class);
        var therm = (ThermostatDevice) result;
        assertThat(therm.deviceClass()).isEqualTo(DeviceClass.THERMOSTAT);
        assertThat(therm.mode()).isEqualTo(ThermostatMode.COOL);
    }

    // ---- 16. Unknown tag → null ----

    @Test
    void unknownTagReturnsNull() {
        var eq = equipment("UnknownThing", "Unknown",
                List.of("Equipment", "Toaster"));

        var result = mapper.mapEquipment(eq, NOW);

        assertThat(result).isNull();
    }

    // ---- 17. NULL/UNDEF state → available=false ----

    @Test
    void nullUndefStateMarksUnavailable() {
        var eq = equipment("SwitchOffline", "Offline Switch",
                List.of("Equipment", "PowerOutlet"),
                member("Switch", "SwitchOffline_Switch", "NULL",
                        List.of("Point", "Control", "Switch")));

        var result = mapper.mapEquipment(eq, NOW);

        assertThat(result).isNotNull();
        assertThat(result.available()).isFalse();
    }

    // ---- 18. Temperature unit defaults to Celsius ----

    @Test
    void temperatureUnitDefaultsCelsius() {
        var eq = equipment("ThermostatDefault", "Default Thermostat",
                List.of("Equipment", "HVAC"),
                member("Number", "ThermostatDefault_CurrentTemp", "20.0",
                        List.of("Point", "Measurement", "Temperature")),
                member("Number", "ThermostatDefault_TargetTemp", "21.0",
                        List.of("Point", "Setpoint", "Temperature")),
                member("String", "ThermostatDefault_Mode", "heat",
                        List.of()));

        var result = mapper.mapEquipment(eq, NOW);

        assertThat(result).isInstanceOf(ThermostatDevice.class);
        var therm = (ThermostatDevice) result;
        assertThat(therm.currentTemperature().unit()).isEqualTo(Temperature.TemperatureUnit.CELSIUS);
    }

    // ---- 19. Temperature unit detects Fahrenheit ----

    @Test
    void temperatureUnitDetectsFahrenheit() {
        var eq = equipment("ThermostatUS", "US Thermostat",
                List.of("Equipment", "HVAC"),
                memberWithDesc("Number", "ThermostatUS_CurrentTemp", "72.0",
                        List.of("Point", "Measurement", "Temperature"), "%.1f °F"),
                memberWithDesc("Number", "ThermostatUS_TargetTemp", "74.0",
                        List.of("Point", "Setpoint", "Temperature"), "%.1f °F"),
                member("String", "ThermostatUS_Mode", "cool",
                        List.of()));

        var result = mapper.mapEquipment(eq, NOW);

        assertThat(result).isInstanceOf(ThermostatDevice.class);
        var therm = (ThermostatDevice) result;
        assertThat(therm.currentTemperature().unit()).isEqualTo(Temperature.TemperatureUnit.FAHRENHEIT);
        assertThat(therm.targetTemperature().unit()).isEqualTo(Temperature.TemperatureUnit.FAHRENHEIT);
    }

    // ---- 20. HVAC mode resolved from String item ----

    @Test
    void hvacModeResolvedFromStringItem() {
        var eq = equipment("ThermostatMode", "Mode Thermostat",
                List.of("Equipment", "HVAC"),
                member("Number", "ThermostatMode_CurrentTemp", "20.0",
                        List.of("Point", "Measurement", "Temperature")),
                member("Number", "ThermostatMode_TargetTemp", "21.0",
                        List.of("Point", "Setpoint", "Temperature")),
                member("String", "ThermostatMode_HvacMode", "auto",
                        List.of()));

        var result = mapper.mapEquipment(eq, NOW);

        assertThat(result).isInstanceOf(ThermostatDevice.class);
        var therm = (ThermostatDevice) result;
        assertThat(therm.mode()).isEqualTo(ThermostatMode.AUTO);
    }

    // ---- 21. HVAC mode defaults to OFF when no mode item ----

    @Test
    void hvacModeDefaultsToOffWhenNoModeItem() {
        var eq = equipment("ThermostatNoMode", "No Mode Thermostat",
                List.of("Equipment", "HVAC"),
                member("Number", "ThermostatNoMode_CurrentTemp", "20.0",
                        List.of("Point", "Measurement", "Temperature")),
                member("Number", "ThermostatNoMode_TargetTemp", "21.0",
                        List.of("Point", "Setpoint", "Temperature")));

        var result = mapper.mapEquipment(eq, NOW);

        assertThat(result).isInstanceOf(ThermostatDevice.class);
        var therm = (ThermostatDevice) result;
        assertThat(therm.mode()).isEqualTo(ThermostatMode.OFF);
    }

    // ---- 22. Device ID is equipment name ----

    @Test
    void deviceIdIsEquipmentName() {
        var eq = equipment("MyUniqueSwitch", "A Switch",
                List.of("Equipment", "WallSwitch"),
                member("Switch", "MyUniqueSwitch_Toggle", "OFF",
                        List.of("Point", "Control", "Switch")));

        var result = mapper.mapEquipment(eq, NOW);

        assertThat(result).isNotNull();
        assertThat(result.deviceId()).isEqualTo("MyUniqueSwitch");
        assertThat(result.label()).isEqualTo("A Switch");
        assertThat(result.lastUpdated()).isEqualTo(NOW);
        assertThat(result.tenancyId()).isEqualTo("test-tenant");
    }
}
