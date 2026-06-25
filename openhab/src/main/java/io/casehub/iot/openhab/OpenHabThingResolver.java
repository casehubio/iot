package io.casehub.iot.openhab;

import io.casehub.iot.api.DeviceClass;
import io.casehub.iot.api.SensorType;
import io.casehub.iot.api.ThermostatMode;
import io.casehub.iot.openhab.internal.OpenHabChannelDto;
import io.casehub.iot.openhab.internal.OpenHabItemDto;
import io.casehub.iot.openhab.internal.OpenHabThingDto;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves an OpenHAB Thing and its linked items' states to {@link ResolvedDeviceFields}
 * using a two-signal model: thing-type category (binding-maintained metadata) merged with
 * channel itemType inference. Revealing categories (Lock, MotionDetector, Lightbulb)
 * override channels; coarse categories (Sensor) defer to channel refinement.
 */
public class OpenHabThingResolver {

    private static final int PRIORITY_COLOR = 1;
    private static final int PRIORITY_DIMMER = 2;
    private static final int PRIORITY_ROLLERSHUTTER = 3;
    private static final int PRIORITY_PLAYER = 4;
    private static final int PRIORITY_POWER_ENERGY = 5;
    private static final int PRIORITY_THERMOSTAT = 6;
    private static final int PRIORITY_TEMPERATURE = 7;
    private static final int PRIORITY_HUMIDITY = 8;
    private static final int PRIORITY_SWITCH = 9;
    private static final int PRIORITY_CONTACT = 10;
    private static final int PRIORITY_BARE_NUMBER = 11;
    private static final int PRIORITY_NONE = Integer.MAX_VALUE;

    private final String tenancyId;
    private final Map<String, String> thingTypeCategories;

    public OpenHabThingResolver(String tenancyId, Map<String, String> thingTypeCategories) {
        this.tenancyId = tenancyId;
        this.thingTypeCategories = Map.copyOf(thingTypeCategories);
    }

    public ResolvedDeviceFields resolve(OpenHabThingDto thing,
                                        Map<String, OpenHabItemDto> itemStates,
                                        Instant now) {
        List<OpenHabChannelDto> stateChannels = thing.stateChannels();

        DeviceClass channelClass = inferDeviceClass(stateChannels);
        DeviceClass categoryClass = mapCategory(thing.thingTypeUID());
        DeviceClass deviceClass = mergeInference(channelClass, categoryClass);
        if (deviceClass == null) {
            return null;
        }

        String category = thing.thingTypeUID() != null
                ? thingTypeCategories.get(thing.thingTypeUID()) : null;

        ResolvedDeviceFields.Builder b = ResolvedDeviceFields.builder()
                .deviceId(thing.uid())
                .label(thing.label() != null ? thing.label() : thing.uid())
                .available(thing.isOnline())
                .now(now)
                .tenancyId(tenancyId)
                .deviceClass(deviceClass);

        populateFields(b, deviceClass, stateChannels, itemStates);
        enrichSensorType(b, deviceClass, category);

        return b.build();
    }

    // ---- category → DeviceClass mapping (case-insensitive) ----

    DeviceClass mapCategory(String thingTypeUID) {
        if (thingTypeUID == null) return null;
        String category = thingTypeCategories.get(thingTypeUID);
        if (category == null) return null;
        return switch (category.toLowerCase(Locale.ROOT)) {
            case "lightbulb" -> DeviceClass.LIGHT;
            case "radiatorcontrol" -> DeviceClass.THERMOSTAT;
            case "lock" -> DeviceClass.LOCK;
            case "blinds" -> DeviceClass.COVER;
            case "poweroutlet", "wallswitch" -> DeviceClass.SWITCH;
            case "inverter" -> DeviceClass.POWER_SENSOR;
            case "sensor", "smokedetector", "door", "frontdoor", "window" -> DeviceClass.SENSOR;
            case "motiondetector" -> DeviceClass.PRESENCE_SENSOR;
            case "speaker", "receiver", "screen", "projector" -> DeviceClass.MEDIA_PLAYER;
            case "camera" -> DeviceClass.CAMERA;
            default -> null;
        };
    }

    DeviceClass mergeInference(DeviceClass channelClass, DeviceClass categoryClass) {
        if (categoryClass != null && categoryClass != DeviceClass.SENSOR) {
            return categoryClass;
        }
        if (categoryClass == DeviceClass.SENSOR) {
            return channelClass != null ? channelClass : DeviceClass.SENSOR;
        }
        return channelClass;
    }

    // ---- SensorType enrichment from category ----

    private void enrichSensorType(ResolvedDeviceFields.Builder b, DeviceClass deviceClass, String category) {
        if (deviceClass != DeviceClass.SENSOR || category == null) return;
        SensorType current = b.currentSensorType();
        if (current != null && current != SensorType.GENERIC) return;
        switch (category.toLowerCase(Locale.ROOT)) {
            case "door", "frontdoor", "window" -> b.sensorType(SensorType.DOOR_WINDOW);
            default -> { }
        }
    }

    // ---- DeviceClass inference (priority-based, scan all channels) ----

    private DeviceClass inferDeviceClass(List<OpenHabChannelDto> stateChannels) {
        int bestPriority = PRIORITY_NONE;
        DeviceClass bestClass = null;
        int temperatureCount = 0;
        boolean hasSetpointTemp = false;

        for (OpenHabChannelDto ch : stateChannels) {
            String itemType = ch.itemType();
            if (itemType == null) continue;

            int priority = channelPriority(itemType);
            if (priority < bestPriority) {
                bestPriority = priority;
                bestClass = channelToDeviceClass(itemType);
            }

            if ("Number:Temperature".equals(itemType)) {
                temperatureCount++;
                if (isSetpointChannel(ch)) {
                    hasSetpointTemp = true;
                }
            }
        }

        if (temperatureCount >= 2 && hasSetpointTemp && PRIORITY_THERMOSTAT < bestPriority) {
            return DeviceClass.THERMOSTAT;
        }

        return bestClass;
    }

    private int channelPriority(String itemType) {
        return switch (itemType) {
            case "Color" -> PRIORITY_COLOR;
            case "Dimmer" -> PRIORITY_DIMMER;
            case "Rollershutter" -> PRIORITY_ROLLERSHUTTER;
            case "Player" -> PRIORITY_PLAYER;
            case "Number:Power", "Number:Energy" -> PRIORITY_POWER_ENERGY;
            case "Number:Temperature" -> PRIORITY_TEMPERATURE;
            case "Number:Humidity" -> PRIORITY_HUMIDITY;
            case "Switch" -> PRIORITY_SWITCH;
            case "Contact" -> PRIORITY_CONTACT;
            case "Number" -> PRIORITY_BARE_NUMBER;
            default -> PRIORITY_NONE;
        };
    }

    private DeviceClass channelToDeviceClass(String itemType) {
        return switch (itemType) {
            case "Color", "Dimmer" -> DeviceClass.LIGHT;
            case "Rollershutter" -> DeviceClass.COVER;
            case "Player" -> DeviceClass.MEDIA_PLAYER;
            case "Number:Power", "Number:Energy" -> DeviceClass.POWER_SENSOR;
            case "Number:Temperature" -> DeviceClass.SENSOR;
            case "Number:Humidity" -> DeviceClass.SENSOR;
            case "Switch" -> DeviceClass.SWITCH;
            case "Contact" -> DeviceClass.SENSOR;
            case "Number" -> DeviceClass.SENSOR;
            default -> null;
        };
    }

    private boolean isSetpointChannel(OpenHabChannelDto ch) {
        return ch.isSetpointChannel();
    }

    // ---- field population ----

    private void populateFields(ResolvedDeviceFields.Builder b, DeviceClass deviceClass,
                                List<OpenHabChannelDto> stateChannels,
                                Map<String, OpenHabItemDto> itemStates) {
        for (OpenHabChannelDto ch : stateChannels) {
            String itemType = ch.itemType();
            if (itemType == null) continue;

            String state = resolveState(ch, itemStates);

            switch (itemType) {
                case "Color" -> populateColor(b, state);
                case "Dimmer" -> populateDimmer(b, state, deviceClass);
                case "Switch" -> populateSwitch(b, state, deviceClass);
                case "Rollershutter" -> populateRollershutter(b, state);
                case "Number:Temperature" -> populateTemperature(b, ch, itemStates, deviceClass);
                case "Number:Power" -> b.power(OpenHabEntityMapper.parseOrNull(state));
                case "Number:Energy" -> b.energy(OpenHabEntityMapper.parseOrNull(state));
                case "Number:Humidity" -> {
                    b.numericValue(OpenHabEntityMapper.parseOrNull(state));
                    b.sensorType(SensorType.HUMIDITY);
                }
                case "Number" -> {
                    b.numericValue(OpenHabEntityMapper.parseOrNull(state));
                    b.sensorType(SensorType.GENERIC);
                }
                case "Contact" -> {
                    b.sensorType(SensorType.GENERIC);
                    populateContact(b, state, deviceClass);
                }
                case "String" -> { /* handled separately for thermostat mode */ }
                default -> { /* ignore unmapped channel types */ }
            }
        }

        if (deviceClass == DeviceClass.SENSOR) {
            for (OpenHabChannelDto ch : stateChannels) {
                if ("Number:Temperature".equals(ch.itemType())) {
                    b.sensorType(SensorType.TEMPERATURE);
                    String state = resolveState(ch, itemStates);
                    b.numericValue(OpenHabEntityMapper.parseOrNull(state));
                    break;
                }
            }
        }

        if (deviceClass == DeviceClass.THERMOSTAT) {
            b.mode(resolveThingHvacMode(stateChannels, itemStates));
        }

        if (deviceClass == DeviceClass.COVER) {
            b.isRollershutter(true);
        }
    }

    private void populateColor(ResolvedDeviceFields.Builder b, String state) {
        OpenHabHsbType hsb = OpenHabEntityMapper.parseHsb(state);
        b.hsb(hsb);
        if (hsb != null) {
            b.brightness(hsb.brightness().intValue());
            b.on(hsb.brightness().compareTo(BigDecimal.ZERO) > 0);
        }
    }

    private void populateDimmer(ResolvedDeviceFields.Builder b, String state, DeviceClass deviceClass) {
        if (deviceClass != DeviceClass.LIGHT) return;
        Integer brightness = OpenHabEntityMapper.parseIntOrNull(state);
        b.brightness(brightness);
        b.on(brightness != null && brightness > 0);
    }

    private void populateSwitch(ResolvedDeviceFields.Builder b, String state, DeviceClass deviceClass) {
        if (deviceClass == DeviceClass.SWITCH) {
            b.on("ON".equals(state));
        } else if (deviceClass == DeviceClass.LOCK) {
            b.locked("ON".equals(state));
        } else if (deviceClass == DeviceClass.PRESENCE_SENSOR) {
            b.present("ON".equals(state));
        }
    }

    private void populateContact(ResolvedDeviceFields.Builder b, String state, DeviceClass deviceClass) {
        if (deviceClass == DeviceClass.LOCK) {
            b.locked("CLOSED".equals(state));
        } else if (deviceClass == DeviceClass.PRESENCE_SENSOR) {
            b.present("OPEN".equals(state));
        }
    }

    private void populateRollershutter(ResolvedDeviceFields.Builder b, String state) {
        Integer raw = OpenHabEntityMapper.parseIntOrNull(state);
        if (raw != null) {
            b.position(100 - raw);
        }
    }

    private void populateTemperature(ResolvedDeviceFields.Builder b, OpenHabChannelDto ch,
                                     Map<String, OpenHabItemDto> itemStates, DeviceClass deviceClass) {
        if (deviceClass != DeviceClass.THERMOSTAT) return;

        OpenHabItemDto item = resolveItem(ch, itemStates);
        if (item == null) return;

        if (isSetpointChannel(ch)) {
            b.targetTemperature(OpenHabEntityMapper.parseTemperature(item));
        } else {
            b.currentTemperature(OpenHabEntityMapper.parseTemperature(item));
        }
    }

    // ---- thermostat mode for Thing path ----

    private ThermostatMode resolveThingHvacMode(List<OpenHabChannelDto> stateChannels,
                                                 Map<String, OpenHabItemDto> itemStates) {
        for (OpenHabChannelDto ch : stateChannels) {
            if ("Switch".equals(ch.itemType())) {
                String state = resolveState(ch, itemStates);
                if (!"ON".equals(state)) {
                    return ThermostatMode.OFF;
                }
            }
        }

        for (OpenHabChannelDto ch : stateChannels) {
            if ("String".equals(ch.itemType())) {
                String combined = ((ch.channelTypeUID() != null ? ch.channelTypeUID() : "")
                        + " " + ch.id()).toLowerCase(Locale.ROOT);
                if (combined.contains("mode")) {
                    String state = resolveState(ch, itemStates);
                    return OpenHabEntityMapper.mapHvacModeString(state);
                }
            }
        }

        return ThermostatMode.OFF;
    }

    // ---- utility ----

    private String resolveState(OpenHabChannelDto ch, Map<String, OpenHabItemDto> itemStates) {
        if (ch.linkedItems() == null || ch.linkedItems().isEmpty()) return null;
        String itemName = ch.linkedItems().get(0);
        OpenHabItemDto item = itemStates.get(itemName);
        return item != null ? item.state() : null;
    }

    private OpenHabItemDto resolveItem(OpenHabChannelDto ch, Map<String, OpenHabItemDto> itemStates) {
        if (ch.linkedItems() == null || ch.linkedItems().isEmpty()) return null;
        String itemName = ch.linkedItems().get(0);
        return itemStates.get(itemName);
    }
}
