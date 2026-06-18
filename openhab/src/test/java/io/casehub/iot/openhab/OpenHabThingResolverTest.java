package io.casehub.iot.openhab;

import io.casehub.iot.api.*;
import io.casehub.iot.openhab.internal.OpenHabChannelDto;
import io.casehub.iot.openhab.internal.OpenHabItemDto;
import io.casehub.iot.openhab.internal.OpenHabStatusInfoDto;
import io.casehub.iot.openhab.internal.OpenHabThingDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenHabThingResolverTest {

    private static final Instant NOW = Instant.parse("2026-06-15T12:00:00Z");
    private static final String TENANT = "test-tenant";

    private final OpenHabThingResolver resolver = new OpenHabThingResolver(TENANT, Map.of());

    // ---- helper methods ----

    private OpenHabThingDto thing(String uid, String label, String status, OpenHabChannelDto... channels) {
        return new OpenHabThingDto(uid, label, "binding:type",
                new OpenHabStatusInfoDto(status, "NONE"), List.of(channels), null);
    }

    private OpenHabThingDto thingWithType(String uid, String label, String thingTypeUID,
                                          String status, OpenHabChannelDto... channels) {
        return new OpenHabThingDto(uid, label, thingTypeUID,
                new OpenHabStatusInfoDto(status, "NONE"), List.of(channels), null);
    }

    private OpenHabChannelDto channel(String id, String itemType, String... linkedItems) {
        return new OpenHabChannelDto("thing:" + id, id, "system:" + id,
                itemType, "STATE", List.of(linkedItems), List.of());
    }

    private OpenHabChannelDto triggerChannel(String id, String itemType, String... linkedItems) {
        return new OpenHabChannelDto("thing:" + id, id, "system:" + id,
                itemType, "TRIGGER", List.of(linkedItems), List.of());
    }

    private OpenHabChannelDto channelWithType(String id, String itemType, String channelTypeUID, String... linkedItems) {
        return new OpenHabChannelDto("thing:" + id, id, channelTypeUID,
                itemType, "STATE", List.of(linkedItems), List.of());
    }

    private Map<String, OpenHabItemDto> items(String... pairs) {
        Map<String, OpenHabItemDto> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], new OpenHabItemDto(null, pairs[i], pairs[i], pairs[i + 1], null, null, null));
        }
        return map;
    }

    private OpenHabThingResolver resolverWithCategories(Map<String, String> categories) {
        return new OpenHabThingResolver(TENANT, categories);
    }

    // =====================================================================
    //  Channel-based inference (existing tests — unchanged behavior)
    // =====================================================================

    @Test
    void colorChannelMapsToLightWithHsb() {
        var t = thing("thing:color1", "Color Light", "ONLINE",
                channel("color", "Color", "colorItem"));
        var itemStates = items("colorItem", "240,100,50");

        var fields = resolver.resolve(t, itemStates, NOW);

        assertThat(fields).isNotNull();
        assertThat(fields.deviceClass()).isEqualTo(DeviceClass.LIGHT);
        assertThat(fields.hsb()).isNotNull();
        assertThat(fields.hsb().hue()).isEqualByComparingTo("240");
        assertThat(fields.hsb().saturation()).isEqualByComparingTo("100");
        assertThat(fields.hsb().brightness()).isEqualByComparingTo("50");
        assertThat(fields.brightness()).isEqualTo(50);
        assertThat(fields.on()).isTrue();
    }

    @Test
    void dimmerChannelMapsToLightWithBrightness() {
        var t = thing("thing:dimmer1", "Dimmer Light", "ONLINE",
                channel("brightness", "Dimmer", "dimmerItem"));
        var itemStates = items("dimmerItem", "75");

        var fields = resolver.resolve(t, itemStates, NOW);

        assertThat(fields).isNotNull();
        assertThat(fields.deviceClass()).isEqualTo(DeviceClass.LIGHT);
        assertThat(fields.brightness()).isEqualTo(75);
        assertThat(fields.on()).isTrue();
    }

    @Test
    void switchOnlyMapsToSwitch() {
        var t = thing("thing:switch1", "Wall Switch", "ONLINE",
                channel("power", "Switch", "switchItem"));
        var itemStates = items("switchItem", "ON");

        var fields = resolver.resolve(t, itemStates, NOW);

        assertThat(fields).isNotNull();
        assertThat(fields.deviceClass()).isEqualTo(DeviceClass.SWITCH);
        assertThat(fields.on()).isTrue();
    }

    @Test
    void rollershutterMapsToCoverWithInvertedPosition() {
        var t = thing("thing:blinds1", "Bedroom Blinds", "ONLINE",
                channel("position", "Rollershutter", "blindsItem"));
        var itemStates = items("blindsItem", "30");

        var fields = resolver.resolve(t, itemStates, NOW);

        assertThat(fields).isNotNull();
        assertThat(fields.deviceClass()).isEqualTo(DeviceClass.COVER);
        assertThat(fields.position()).isEqualTo(70);
        assertThat(fields.isRollershutter()).isTrue();
    }

    @Test
    void playerChannelMapsToMediaPlayer() {
        var t = thing("thing:player1", "Media Player", "ONLINE",
                channel("control", "Player", "playerItem"));
        var itemStates = items("playerItem", "PLAY");

        var fields = resolver.resolve(t, itemStates, NOW);

        assertThat(fields).isNotNull();
        assertThat(fields.deviceClass()).isEqualTo(DeviceClass.MEDIA_PLAYER);
    }

    @Test
    void numberPowerMapsToPowerSensor() {
        var t = thing("thing:power1", "Power Meter", "ONLINE",
                channel("power", "Number:Power", "powerItem"));
        var itemStates = items("powerItem", "1500");

        var fields = resolver.resolve(t, itemStates, NOW);

        assertThat(fields).isNotNull();
        assertThat(fields.deviceClass()).isEqualTo(DeviceClass.POWER_SENSOR);
        assertThat(fields.power()).isEqualByComparingTo("1500");
    }

    @Test
    void numberEnergyMapsToPowerSensorWithEnergy() {
        var t = thing("thing:energy1", "Energy Meter", "ONLINE",
                channel("energy", "Number:Energy", "energyItem"));
        var itemStates = items("energyItem", "42.7");

        var fields = resolver.resolve(t, itemStates, NOW);

        assertThat(fields).isNotNull();
        assertThat(fields.deviceClass()).isEqualTo(DeviceClass.POWER_SENSOR);
        assertThat(fields.energy()).isEqualByComparingTo("42.7");
    }

    @Test
    void dualTemperatureWithSetpointMapsThermostat() {
        var t = thing("thing:therm1", "Thermostat", "ONLINE",
                channel("temperature", "Number:Temperature", "tempItem"),
                channelWithType("setpoint", "Number:Temperature",
                        "hvac:setpoint-temperature", "setpointItem"));
        var itemStates = items("tempItem", "21.5", "setpointItem", "22.0");

        var fields = resolver.resolve(t, itemStates, NOW);

        assertThat(fields).isNotNull();
        assertThat(fields.deviceClass()).isEqualTo(DeviceClass.THERMOSTAT);
        assertThat(fields.currentTemperature().value()).isEqualByComparingTo("21.5");
        assertThat(fields.targetTemperature().value()).isEqualByComparingTo("22.0");
    }

    @Test
    void singleTemperatureMapsToSensor() {
        var t = thing("thing:sensor1", "Temp Sensor", "ONLINE",
                channel("temperature", "Number:Temperature", "tempItem"));
        var itemStates = items("tempItem", "18.3");

        var fields = resolver.resolve(t, itemStates, NOW);

        assertThat(fields).isNotNull();
        assertThat(fields.deviceClass()).isEqualTo(DeviceClass.SENSOR);
        assertThat(fields.sensorType()).isEqualTo(SensorType.TEMPERATURE);
        assertThat(fields.numericValue()).isEqualByComparingTo("18.3");
    }

    @Test
    void numberHumidityMapsToSensorHumidity() {
        var t = thing("thing:humidity1", "Humidity Sensor", "ONLINE",
                channel("humidity", "Number:Humidity", "humidityItem"));
        var itemStates = items("humidityItem", "68.5");

        var fields = resolver.resolve(t, itemStates, NOW);

        assertThat(fields).isNotNull();
        assertThat(fields.deviceClass()).isEqualTo(DeviceClass.SENSOR);
        assertThat(fields.sensorType()).isEqualTo(SensorType.HUMIDITY);
        assertThat(fields.numericValue()).isEqualByComparingTo("68.5");
    }

    @Test
    void contactMapsToSensorGeneric() {
        var t = thing("thing:contact1", "Door Sensor", "ONLINE",
                channel("state", "Contact", "contactItem"));
        var itemStates = items("contactItem", "OPEN");

        var fields = resolver.resolve(t, itemStates, NOW);

        assertThat(fields).isNotNull();
        assertThat(fields.deviceClass()).isEqualTo(DeviceClass.SENSOR);
        assertThat(fields.sensorType()).isEqualTo(SensorType.GENERIC);
    }

    @Test
    void bareNumberMapsToSensorGeneric() {
        var t = thing("thing:number1", "Generic Number", "ONLINE",
                channel("value", "Number", "numberItem"));
        var itemStates = items("numberItem", "42");

        var fields = resolver.resolve(t, itemStates, NOW);

        assertThat(fields).isNotNull();
        assertThat(fields.deviceClass()).isEqualTo(DeviceClass.SENSOR);
        assertThat(fields.sensorType()).isEqualTo(SensorType.GENERIC);
        assertThat(fields.numericValue()).isEqualByComparingTo("42");
    }

    @Test
    void offlineThingMarksUnavailable() {
        var t = thing("thing:offline1", "Offline Switch", "OFFLINE",
                channel("power", "Switch", "switchItem"));
        var itemStates = items("switchItem", "ON");

        var fields = resolver.resolve(t, itemStates, NOW);

        assertThat(fields).isNotNull();
        assertThat(fields.available()).isFalse();
    }

    @Test
    void noMappableChannelsReturnsNull() {
        var t = thing("thing:empty1", "Empty Thing", "ONLINE");
        Map<String, OpenHabItemDto> itemStates = Map.of();

        var fields = resolver.resolve(t, itemStates, NOW);

        assertThat(fields).isNull();
    }

    @Test
    void triggerChannelsExcludedFromInference() {
        var t = thing("thing:trigger1", "Trigger Only", "ONLINE",
                triggerChannel("button", "String", "buttonItem"));
        var itemStates = items("buttonItem", "PRESSED");

        var fields = resolver.resolve(t, itemStates, NOW);

        assertThat(fields).isNull();
    }

    @Test
    void prioritySwitchBeatsNumber() {
        var t = thing("thing:priority1", "Switch + Number", "ONLINE",
                channel("value", "Number", "numberItem"),
                channel("power", "Switch", "switchItem"));
        var itemStates = items("numberItem", "42", "switchItem", "ON");

        var fields = resolver.resolve(t, itemStates, NOW);

        assertThat(fields).isNotNull();
        assertThat(fields.deviceClass()).isEqualTo(DeviceClass.SWITCH);
        assertThat(fields.on()).isTrue();
    }

    @Test
    void priorityColorBeatsSwitch() {
        var t = thing("thing:priority2", "Color + Switch", "ONLINE",
                channel("power", "Switch", "switchItem"),
                channel("color", "Color", "colorItem"));
        var itemStates = items("switchItem", "ON", "colorItem", "240,100,50");

        var fields = resolver.resolve(t, itemStates, NOW);

        assertThat(fields).isNotNull();
        assertThat(fields.deviceClass()).isEqualTo(DeviceClass.LIGHT);
        assertThat(fields.hsb()).isNotNull();
    }

    @Test
    void thermostatModeFromStringChannel() {
        var t = thing("thing:therm2", "Mode Thermostat", "ONLINE",
                channel("temperature", "Number:Temperature", "tempItem"),
                channelWithType("setpoint", "Number:Temperature",
                        "hvac:setpoint-temperature", "setpointItem"),
                channel("hvac_mode", "String", "modeItem"));
        var itemStates = items("tempItem", "20.0", "setpointItem", "21.0",
                "modeItem", "heat");

        var fields = resolver.resolve(t, itemStates, NOW);

        assertThat(fields).isNotNull();
        assertThat(fields.deviceClass()).isEqualTo(DeviceClass.THERMOSTAT);
        assertThat(fields.mode()).isEqualTo(ThermostatMode.HEAT);
    }

    @Test
    void thermostatModeDefaultsToOff() {
        var t = thing("thing:therm3", "No Mode Thermostat", "ONLINE",
                channel("temperature", "Number:Temperature", "tempItem"),
                channelWithType("setpoint", "Number:Temperature",
                        "hvac:setpoint-temperature", "setpointItem"));
        var itemStates = items("tempItem", "20.0", "setpointItem", "21.0");

        var fields = resolver.resolve(t, itemStates, NOW);

        assertThat(fields).isNotNull();
        assertThat(fields.deviceClass()).isEqualTo(DeviceClass.THERMOSTAT);
        assertThat(fields.mode()).isEqualTo(ThermostatMode.OFF);
    }

    @Test
    void thermostatSwitchOffOverridesMode() {
        var t = thing("thing:therm4", "Switch Off Thermostat", "ONLINE",
                channel("temperature", "Number:Temperature", "tempItem"),
                channelWithType("setpoint", "Number:Temperature",
                        "hvac:setpoint-temperature", "setpointItem"),
                channel("hvac_mode", "String", "modeItem"),
                channel("power", "Switch", "switchItem"));
        var itemStates = items("tempItem", "20.0", "setpointItem", "21.0",
                "modeItem", "heat", "switchItem", "OFF");

        var fields = resolver.resolve(t, itemStates, NOW);

        assertThat(fields).isNotNull();
        assertThat(fields.deviceClass()).isEqualTo(DeviceClass.THERMOSTAT);
        assertThat(fields.mode()).isEqualTo(ThermostatMode.OFF);
    }

    // =====================================================================
    //  Category-based inference (new tests)
    // =====================================================================

    @Test
    void revealingCategoryMapsToDeviceClass() {
        var categories = Map.of("hue:0210", "Lightbulb");
        var r = resolverWithCategories(categories);
        var t = thingWithType("thing:hue1", "Hue Bulb", "hue:0210", "ONLINE",
                channel("power", "Switch", "switchItem"));
        var itemStates = items("switchItem", "ON");

        var fields = r.resolve(t, itemStates, NOW);

        assertThat(fields).isNotNull();
        assertThat(fields.deviceClass()).isEqualTo(DeviceClass.LIGHT);
    }

    @Test
    void lockCategoryWithSwitchOnPopulatesLocked() {
        var categories = Map.of("yale:lock", "Lock");
        var r = resolverWithCategories(categories);
        var t = thingWithType("thing:lock1", "Front Door", "yale:lock", "ONLINE",
                channel("lock", "Switch", "lockItem"));
        var itemStates = items("lockItem", "ON");

        var fields = r.resolve(t, itemStates, NOW);

        assertThat(fields).isNotNull();
        assertThat(fields.deviceClass()).isEqualTo(DeviceClass.LOCK);
        assertThat(fields.locked()).isTrue();
    }

    @Test
    void lockCategoryWithContactClosedPopulatesLocked() {
        var categories = Map.of("yale:lock", "Lock");
        var r = resolverWithCategories(categories);
        var t = thingWithType("thing:lock2", "Back Door", "yale:lock", "ONLINE",
                channel("state", "Contact", "contactItem"));
        var itemStates = items("contactItem", "CLOSED");

        var fields = r.resolve(t, itemStates, NOW);

        assertThat(fields).isNotNull();
        assertThat(fields.deviceClass()).isEqualTo(DeviceClass.LOCK);
        assertThat(fields.locked()).isTrue();
    }

    @Test
    void lockCategoryWithNoChannelsDefaultsToUnlocked() {
        var categories = Map.of("yale:lock", "Lock");
        var r = resolverWithCategories(categories);
        var t = thingWithType("thing:lock3", "Garage Lock", "yale:lock", "ONLINE");

        var fields = r.resolve(t, Map.of(), NOW);

        assertThat(fields).isNotNull();
        assertThat(fields.deviceClass()).isEqualTo(DeviceClass.LOCK);
        assertThat(fields.locked()).isNull();
    }

    @Test
    void motionDetectorWithSwitchOnPopulatesPresent() {
        var categories = Map.of("zwave:motion", "MotionDetector");
        var r = resolverWithCategories(categories);
        var t = thingWithType("thing:motion1", "Hallway Motion", "zwave:motion", "ONLINE",
                channel("alarm", "Switch", "motionItem"));
        var itemStates = items("motionItem", "ON");

        var fields = r.resolve(t, itemStates, NOW);

        assertThat(fields).isNotNull();
        assertThat(fields.deviceClass()).isEqualTo(DeviceClass.PRESENCE_SENSOR);
        assertThat(fields.present()).isTrue();
    }

    @Test
    void motionDetectorWithContactOpenPopulatesPresent() {
        var categories = Map.of("zwave:motion", "MotionDetector");
        var r = resolverWithCategories(categories);
        var t = thingWithType("thing:motion2", "Doorway Motion", "zwave:motion", "ONLINE",
                channel("state", "Contact", "contactItem"));
        var itemStates = items("contactItem", "OPEN");

        var fields = r.resolve(t, itemStates, NOW);

        assertThat(fields).isNotNull();
        assertThat(fields.deviceClass()).isEqualTo(DeviceClass.PRESENCE_SENSOR);
        assertThat(fields.present()).isTrue();
    }

    @Test
    void sensorCategoryDefersToChannelRefinement() {
        var categories = Map.of("shelly:pm", "Sensor");
        var r = resolverWithCategories(categories);
        var t = thingWithType("thing:pm1", "Power Monitor", "shelly:pm", "ONLINE",
                channel("power", "Number:Power", "powerItem"));
        var itemStates = items("powerItem", "1500");

        var fields = r.resolve(t, itemStates, NOW);

        assertThat(fields).isNotNull();
        assertThat(fields.deviceClass()).isEqualTo(DeviceClass.POWER_SENSOR);
    }

    @Test
    void sensorCategoryWithThermostatDisambiguation() {
        var categories = Map.of("knx:device", "Sensor");
        var r = resolverWithCategories(categories);
        var t = thingWithType("thing:knx1", "KNX HVAC", "knx:device", "ONLINE",
                channel("temperature", "Number:Temperature", "tempItem"),
                channelWithType("setpoint", "Number:Temperature",
                        "hvac:setpoint-temperature", "setpointItem"));
        var itemStates = items("tempItem", "21.5", "setpointItem", "22.0");

        var fields = r.resolve(t, itemStates, NOW);

        assertThat(fields).isNotNull();
        assertThat(fields.deviceClass()).isEqualTo(DeviceClass.THERMOSTAT);
    }

    @Test
    void sensorCategoryWithNoRefinableChannels() {
        var categories = Map.of("generic:sensor", "Sensor");
        var r = resolverWithCategories(categories);
        var t = thingWithType("thing:generic1", "Generic Sensor", "generic:sensor", "ONLINE",
                channel("state", "String", "stringItem"));
        var itemStates = items("stringItem", "OK");

        var fields = r.resolve(t, itemStates, NOW);

        assertThat(fields).isNotNull();
        assertThat(fields.deviceClass()).isEqualTo(DeviceClass.SENSOR);
    }

    @Test
    void doorCategoryEnrichesSensorTypeToDoorWindow() {
        var categories = Map.of("zwave:door", "Door");
        var r = resolverWithCategories(categories);
        var t = thingWithType("thing:door1", "Front Door", "zwave:door", "ONLINE",
                channel("state", "Contact", "contactItem"));
        var itemStates = items("contactItem", "OPEN");

        var fields = r.resolve(t, itemStates, NOW);

        assertThat(fields).isNotNull();
        assertThat(fields.deviceClass()).isEqualTo(DeviceClass.SENSOR);
        assertThat(fields.sensorType()).isEqualTo(SensorType.DOOR_WINDOW);
    }

    @Test
    void unknownCategoryFallsThroughToChannelInference() {
        var categories = Map.of("ring:camera", "Camera");
        var r = resolverWithCategories(categories);
        var t = thingWithType("thing:cam1", "Ring Camera", "ring:camera", "ONLINE",
                channel("power", "Switch", "switchItem"));
        var itemStates = items("switchItem", "ON");

        var fields = r.resolve(t, itemStates, NOW);

        assertThat(fields).isNotNull();
        assertThat(fields.deviceClass()).isEqualTo(DeviceClass.SWITCH);
    }

    @Test
    void nullCategoryFallsThroughToChannelInference() {
        var t = thing("thing:nocat1", "No Category", "ONLINE",
                channel("power", "Switch", "switchItem"));
        var itemStates = items("switchItem", "ON");

        var fields = resolver.resolve(t, itemStates, NOW);

        assertThat(fields).isNotNull();
        assertThat(fields.deviceClass()).isEqualTo(DeviceClass.SWITCH);
    }

    @Test
    void hvacFallsThroughToChannelInference() {
        var categories = Map.of("daikin:ac", "HVAC");
        var r = resolverWithCategories(categories);
        var t = thingWithType("thing:ac1", "Daikin AC", "daikin:ac", "ONLINE",
                channel("power", "Switch", "switchItem"));
        var itemStates = items("switchItem", "ON");

        var fields = r.resolve(t, itemStates, NOW);

        assertThat(fields).isNotNull();
        assertThat(fields.deviceClass()).isEqualTo(DeviceClass.SWITCH);
    }

    @Test
    void garageDoorFallsThroughToChannelInference() {
        var categories = Map.of("gogogate:garage", "GarageDoor");
        var r = resolverWithCategories(categories);
        var t = thingWithType("thing:garage1", "Garage", "gogogate:garage", "ONLINE",
                channel("power", "Switch", "switchItem"));
        var itemStates = items("switchItem", "ON");

        var fields = r.resolve(t, itemStates, NOW);

        assertThat(fields).isNotNull();
        assertThat(fields.deviceClass()).isEqualTo(DeviceClass.SWITCH);
    }

    @Test
    void categoryRescuesNullChannelThing() {
        var categories = Map.of("yale:lock", "Lock");
        var r = resolverWithCategories(categories);
        var t = thingWithType("thing:lock4", "Smart Lock", "yale:lock", "ONLINE",
                channel("state", "String", "stringItem"));
        var itemStates = items("stringItem", "LOCKED");

        var fields = r.resolve(t, itemStates, NOW);

        assertThat(fields).isNotNull();
        assertThat(fields.deviceClass()).isEqualTo(DeviceClass.LOCK);
    }

    @Test
    void categoryMatchingIsCaseInsensitive() {
        var categories = Map.of("hue:bulb", "lightbulb");
        var r = resolverWithCategories(categories);
        var t = thingWithType("thing:hue2", "Hue Light", "hue:bulb", "ONLINE",
                channel("power", "Switch", "switchItem"));
        var itemStates = items("switchItem", "ON");

        var fields = r.resolve(t, itemStates, NOW);

        assertThat(fields).isNotNull();
        assertThat(fields.deviceClass()).isEqualTo(DeviceClass.LIGHT);
    }

    @Test
    void emptyCategoryMapDegracesGracefully() {
        var r = resolverWithCategories(Map.of());
        var t = thing("thing:switch2", "Switch", "ONLINE",
                channel("power", "Switch", "switchItem"));
        var itemStates = items("switchItem", "ON");

        var fields = r.resolve(t, itemStates, NOW);

        assertThat(fields).isNotNull();
        assertThat(fields.deviceClass()).isEqualTo(DeviceClass.SWITCH);
    }
}
