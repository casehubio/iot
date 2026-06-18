package io.casehub.iot.openhab;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.iot.api.*;
import io.casehub.iot.openhab.internal.OpenHabChannelDto;
import io.casehub.iot.openhab.internal.OpenHabItemDto;
import io.casehub.iot.openhab.internal.OpenHabSseEventDto;
import io.casehub.iot.openhab.internal.OpenHabStatePayloadDto;
import io.casehub.iot.openhab.internal.OpenHabStatusInfoDto;
import io.casehub.iot.openhab.internal.OpenHabThingDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link OpenHabSseClient} cache management and item resolution logic.
 *
 * <p>These tests exercise the deterministic (non-CDI) parts of the SSE client:
 * cache population, item-to-Equipment resolution, SSE event handling, and
 * target item resolution for command dispatch. Coalescing timer behaviour
 * requires CDI event infrastructure and is verified in integration tests.</p>
 */
class OpenHabSseClientTest {

    private static final Instant NOW = Instant.parse("2026-06-10T12:00:00Z");

    private OpenHabSseClient sseClient;
    private OpenHabEntityMapper mapper;
    private ObjectMapper objectMapper;
    private OpenHabThingResolver thingResolver;

    @BeforeEach
    void setUp() {
        mapper = new OpenHabEntityMapper("test-tenant");
        objectMapper = new ObjectMapper();
        thingResolver = new OpenHabThingResolver("test-tenant", java.util.Map.of());
        sseClient = new OpenHabSseClient(mapper, objectMapper, thingResolver);
    }

    // ---- helper methods ----

    private OpenHabItemDto equipment(String name, String label, List<String> tags, OpenHabItemDto... members) {
        return new OpenHabItemDto("Group", name, label, "NULL", tags, List.of(members), null);
    }

    private OpenHabItemDto member(String type, String name, String state, List<String> tags) {
        return new OpenHabItemDto(type, name, name, state, tags, null, null);
    }

    private List<OpenHabItemDto> twoEquipments() {
        return List.of(
            equipment("SwitchLiving", "Living Room Switch",
                List.of("Equipment", "PowerOutlet"),
                member("Switch", "SwitchLiving_Toggle", "ON",
                    List.of("Point", "Control", "Switch"))),
            equipment("ThermostatHall", "Hall Thermostat",
                List.of("Equipment", "HVAC"),
                member("Number", "ThermostatHall_CurrentTemp", "20.0",
                    List.of("Point", "Measurement", "Temperature")),
                member("Number", "ThermostatHall_TargetTemp", "21.0",
                    List.of("Point", "Setpoint", "Temperature")),
                member("String", "ThermostatHall_Mode", "heat",
                    List.of()))
        );
    }

    private String sseEventJson(String topic, String newValue) throws Exception {
        var payload = new OpenHabStatePayloadDto("Decimal", newValue, "Decimal", "20.0");
        var payloadJson = objectMapper.writeValueAsString(payload);
        var event = new OpenHabSseEventDto(topic, payloadJson, "ItemStateChangedEvent");
        return objectMapper.writeValueAsString(event);
    }

    // ---- 1. populateCaches populates all indexes ----

    @Test
    void populatesCachesFromDiscoveryResponse() {
        var equipments = twoEquipments();

        sseClient.populateCaches(equipments);

        // itemToEquipment reverse index
        assertThat(sseClient.getItemToEquipment("SwitchLiving_Toggle"))
            .isEqualTo("SwitchLiving");
        assertThat(sseClient.getItemToEquipment("ThermostatHall_CurrentTemp"))
            .isEqualTo("ThermostatHall");
        assertThat(sseClient.getItemToEquipment("ThermostatHall_TargetTemp"))
            .isEqualTo("ThermostatHall");
        assertThat(sseClient.getItemToEquipment("ThermostatHall_Mode"))
            .isEqualTo("ThermostatHall");

        // deviceCache populated
        var devices = sseClient.cachedDevices();
        assertThat(devices).hasSize(2);
        assertThat(devices).extracting(DeviceEntity::deviceId)
            .containsExactlyInAnyOrder("SwitchLiving", "ThermostatHall");

        // itemStateCache populated (accessible via equipment reconstruction)
        var switchDevice = devices.stream()
            .filter(d -> d.deviceId().equals("SwitchLiving")).findFirst().orElseThrow();
        assertThat(switchDevice).isInstanceOf(SwitchDevice.class);
        assertThat(((SwitchDevice) switchDevice).isOn()).isTrue();
    }

    // ---- 2. resolveTargetItem finds Control+Switch for turn_on ----

    @Test
    void resolveTargetItemFindsControlSwitchForTurnOn() {
        sseClient.populateCaches(List.of(
            equipment("SwitchLiving", "Living Room Switch",
                List.of("Equipment", "PowerOutlet"),
                member("Switch", "SwitchLiving_Toggle", "ON",
                    List.of("Point", "Control", "Switch")))));

        var command = DeviceCommand.turnOn("SwitchLiving", Map.of(), "test", "corr-1");
        String targetItem = sseClient.resolveTargetItem(command);

        assertThat(targetItem).isEqualTo("SwitchLiving_Toggle");
    }

    // ---- 3. resolveTargetItem finds Setpoint+Temperature for set_temperature ----

    @Test
    void resolveTargetItemFindsSetpointForSetTemperature() {
        sseClient.populateCaches(List.of(
            equipment("ThermostatHall", "Hall Thermostat",
                List.of("Equipment", "HVAC"),
                member("Number", "ThermostatHall_CurrentTemp", "20.0",
                    List.of("Point", "Measurement", "Temperature")),
                member("Number", "ThermostatHall_TargetTemp", "21.0",
                    List.of("Point", "Setpoint", "Temperature")),
                member("String", "ThermostatHall_Mode", "heat",
                    List.of()))));

        var command = DeviceCommand.setTemperature("ThermostatHall",
            new Temperature(new java.math.BigDecimal("22.0"), Temperature.TemperatureUnit.CELSIUS),
            "test", "corr-2");
        String targetItem = sseClient.resolveTargetItem(command);

        assertThat(targetItem).isEqualTo("ThermostatHall_TargetTemp");
    }

    // ---- 4. resolveTargetItem returns null for unknown device ----

    @Test
    void resolveTargetItemReturnsNullForUnknownDevice() {
        sseClient.populateCaches(twoEquipments());

        var command = DeviceCommand.turnOn("NonExistentDevice", Map.of(), "test", "corr-3");
        String targetItem = sseClient.resolveTargetItem(command);

        assertThat(targetItem).isNull();
    }

    // ---- 5. SSE event for unknown item is ignored ----

    @Test
    void itemNotInEquipmentIsIgnored() throws Exception {
        sseClient.populateCaches(twoEquipments());
        var devicesBefore = sseClient.cachedDevices();

        // Event for an item not in any Equipment
        String eventData = sseEventJson("openhab/items/UnknownItem/statechanged", "42");
        sseClient.handleSseEvent(eventData);

        // Cache unchanged
        assertThat(sseClient.cachedDevices()).containsExactlyInAnyOrderElementsOf(devicesBefore);
    }

    // ---- 6. handleSseEvent updates device cache ----

    @Test
    void handleSseEventUpdatesDeviceCache() throws Exception {
        sseClient.populateCaches(List.of(
            equipment("SwitchLiving", "Living Room Switch",
                List.of("Equipment", "PowerOutlet"),
                member("Switch", "SwitchLiving_Toggle", "ON",
                    List.of("Point", "Control", "Switch")))));

        // Verify initial state
        var deviceBefore = sseClient.cachedDevices().stream()
            .filter(d -> d.deviceId().equals("SwitchLiving")).findFirst().orElseThrow();
        assertThat(((SwitchDevice) deviceBefore).isOn()).isTrue();

        // Simulate state change: switch turned OFF
        var payload = new OpenHabStatePayloadDto("OnOff", "OFF", "OnOff", "ON");
        var payloadJson = objectMapper.writeValueAsString(payload);
        var event = new OpenHabSseEventDto(
            "openhab/items/SwitchLiving_Toggle/statechanged", payloadJson, "ItemStateChangedEvent");
        String eventData = objectMapper.writeValueAsString(event);

        sseClient.handleSseEvent(eventData);

        // Verify cache updated
        var deviceAfter = sseClient.cachedDevices().stream()
            .filter(d -> d.deviceId().equals("SwitchLiving")).findFirst().orElseThrow();
        assertThat(((SwitchDevice) deviceAfter).isOn()).isFalse();
    }

    // ---- 7. resolveTargetItem finds Control+Switch for lock ----

    @Test
    void resolveTargetItemFindsControlSwitchForLock() {
        sseClient.populateCaches(List.of(
            equipment("LockFront", "Front Door Lock",
                List.of("Equipment", "Lock"),
                member("Switch", "LockFront_Switch", "ON",
                    List.of("Point", "Control", "Switch")))));

        var command = DeviceCommand.lock("LockFront", "test", "corr-4");
        String targetItem = sseClient.resolveTargetItem(command);

        assertThat(targetItem).isEqualTo("LockFront_Switch");
    }

    // ---- 8. resolveTargetItem finds Status+OpenState for set_position ----

    @Test
    void resolveTargetItemFindsOpenStateForSetPosition() {
        sseClient.populateCaches(List.of(
            equipment("BlindsBedroom", "Bedroom Blinds",
                List.of("Equipment", "Blinds"),
                member("Rollershutter", "BlindsBedroom_Position", "30",
                    List.of("Point", "Status", "OpenState")))));

        var command = DeviceCommand.setPosition("BlindsBedroom", 70, "test", "corr-5");
        String targetItem = sseClient.resolveTargetItem(command);

        assertThat(targetItem).isEqualTo("BlindsBedroom_Position");
    }

    // ---- 9. resolveTargetItem finds Control+SoundVolume for set_volume ----

    @Test
    void resolveTargetItemFindsControlSoundVolumeForSetVolume() {
        sseClient.populateCaches(List.of(
            equipment("TVLiving", "Living Room TV",
                List.of("Equipment", "Television"),
                member("Dimmer", "TVLiving_Volume", "65",
                    List.of("Point", "Control", "SoundVolume")))));

        var command = DeviceCommand.setVolume("TVLiving", 80, "test", "corr-6");
        String targetItem = sseClient.resolveTargetItem(command);

        assertThat(targetItem).isEqualTo("TVLiving_Volume");
    }

    // ---- 10. cachedDevices returns defensive copy ----

    @Test
    void cachedDevicesReturnsDefensiveCopy() {
        sseClient.populateCaches(twoEquipments());

        var devices1 = sseClient.cachedDevices();
        var devices2 = sseClient.cachedDevices();

        assertThat(devices1).isNotSameAs(devices2);
        assertThat(devices1).containsExactlyInAnyOrderElementsOf(devices2);
    }

    // ---- 11. handleSseEvent with thermostat updates temperature ----

    @Test
    void handleSseEventUpdatesThermostatTemperature() throws Exception {
        sseClient.populateCaches(List.of(
            equipment("ThermostatHall", "Hall Thermostat",
                List.of("Equipment", "HVAC"),
                member("Number", "ThermostatHall_CurrentTemp", "20.0",
                    List.of("Point", "Measurement", "Temperature")),
                member("Number", "ThermostatHall_TargetTemp", "21.0",
                    List.of("Point", "Setpoint", "Temperature")),
                member("String", "ThermostatHall_Mode", "heat",
                    List.of()))));

        // Simulate current temp change to 22.5
        var payload = new OpenHabStatePayloadDto("Decimal", "22.5", "Decimal", "20.0");
        var payloadJson = objectMapper.writeValueAsString(payload);
        var event = new OpenHabSseEventDto(
            "openhab/items/ThermostatHall_CurrentTemp/statechanged", payloadJson, "ItemStateChangedEvent");
        String eventData = objectMapper.writeValueAsString(event);

        sseClient.handleSseEvent(eventData);

        var device = sseClient.cachedDevices().stream()
            .filter(d -> d.deviceId().equals("ThermostatHall")).findFirst().orElseThrow();
        assertThat(device).isInstanceOf(ThermostatDevice.class);
        var therm = (ThermostatDevice) device;
        assertThat(therm.currentTemperature().value())
            .isEqualByComparingTo(new java.math.BigDecimal("22.5"));
        // Target temp unchanged
        assertThat(therm.targetTemperature().value())
            .isEqualByComparingTo(new java.math.BigDecimal("21.0"));
    }

    // ---- 12. multiple SSE events for same equipment update incrementally ----

    @Test
    void multipleEventsForSameEquipmentUpdateIncrementally() throws Exception {
        sseClient.populateCaches(List.of(
            equipment("ThermostatHall", "Hall Thermostat",
                List.of("Equipment", "HVAC"),
                member("Number", "ThermostatHall_CurrentTemp", "20.0",
                    List.of("Point", "Measurement", "Temperature")),
                member("Number", "ThermostatHall_TargetTemp", "21.0",
                    List.of("Point", "Setpoint", "Temperature")),
                member("String", "ThermostatHall_Mode", "heat",
                    List.of()))));

        // First event: current temp changes
        var payload1 = new OpenHabStatePayloadDto("Decimal", "22.0", "Decimal", "20.0");
        var event1 = new OpenHabSseEventDto(
            "openhab/items/ThermostatHall_CurrentTemp/statechanged",
            objectMapper.writeValueAsString(payload1), "ItemStateChangedEvent");
        sseClient.handleSseEvent(objectMapper.writeValueAsString(event1));

        // Second event: target temp changes
        var payload2 = new OpenHabStatePayloadDto("Decimal", "23.0", "Decimal", "21.0");
        var event2 = new OpenHabSseEventDto(
            "openhab/items/ThermostatHall_TargetTemp/statechanged",
            objectMapper.writeValueAsString(payload2), "ItemStateChangedEvent");
        sseClient.handleSseEvent(objectMapper.writeValueAsString(event2));

        var device = sseClient.cachedDevices().stream()
            .filter(d -> d.deviceId().equals("ThermostatHall")).findFirst().orElseThrow();
        var therm = (ThermostatDevice) device;
        // Both updates should be reflected
        assertThat(therm.currentTemperature().value())
            .isEqualByComparingTo(new java.math.BigDecimal("22.0"));
        assertThat(therm.targetTemperature().value())
            .isEqualByComparingTo(new java.math.BigDecimal("23.0"));
    }

    // ---- 13. resolveTargetItem for turn_off finds same item as turn_on ----

    @Test
    void resolveTargetItemFindsSameItemForTurnOff() {
        sseClient.populateCaches(List.of(
            equipment("SwitchLiving", "Living Room Switch",
                List.of("Equipment", "PowerOutlet"),
                member("Switch", "SwitchLiving_Toggle", "ON",
                    List.of("Point", "Control", "Switch")))));

        var command = DeviceCommand.turnOff("SwitchLiving", "test", "corr-7");
        String targetItem = sseClient.resolveTargetItem(command);

        assertThat(targetItem).isEqualTo("SwitchLiving_Toggle");
    }

    // ========================================================================
    // Thing Discovery Pipeline Tests
    // ========================================================================

    // ---- Thing helpers ----

    private OpenHabThingDto thing(String uid, String label, boolean online, OpenHabChannelDto... channels) {
        return new OpenHabThingDto(uid, label, "binding:type:id",
            new OpenHabStatusInfoDto(online ? "ONLINE" : "OFFLINE", null),
            List.of(channels), null);
    }

    private OpenHabChannelDto channel(String id, String itemType, String... linkedItems) {
        return new OpenHabChannelDto(
            "binding:type:id:" + id, id, null, itemType, "STATE",
            List.of(linkedItems), null);
    }

    private OpenHabChannelDto channelWithType(String id, String channelTypeUID, String itemType,
                                               String... linkedItems) {
        return new OpenHabChannelDto(
            "binding:type:id:" + id, id, channelTypeUID, itemType, "STATE",
            List.of(linkedItems), null);
    }

    private OpenHabChannelDto triggerChannel(String id, String itemType, String... linkedItems) {
        return new OpenHabChannelDto(
            "binding:type:id:" + id, id, null, itemType, "TRIGGER",
            List.of(linkedItems), null);
    }

    private OpenHabItemDto item(String type, String name, String state) {
        return new OpenHabItemDto(type, name, name, state, List.of(), null, null);
    }

    private String thingStatusEventJson(String thingUid, String newStatus, String oldStatus) throws Exception {
        var payload = List.of(
            new OpenHabStatusInfoDto(newStatus, null),
            new OpenHabStatusInfoDto(oldStatus, null));
        var payloadJson = objectMapper.writeValueAsString(payload);
        var event = new OpenHabSseEventDto(
            "openhab/things/" + thingUid + "/statuschanged",
            payloadJson, "ThingStatusInfoChangedEvent");
        return objectMapper.writeValueAsString(event);
    }

    // ---- 14. populateThingCaches: unmapped Thing produces device ----

    @Test
    void populateThingCaches_unmappedThingProducesDevice() {
        // Equipment with a toggle item
        sseClient.populateCaches(List.of(
            equipment("SwitchLiving", "Living Room Switch",
                List.of("Equipment", "PowerOutlet"),
                member("Switch", "SwitchLiving_Toggle", "ON",
                    List.of("Point", "Control", "Switch")))));

        // Thing whose linked item is NOT in any Equipment
        OpenHabThingDto sensorThing = thing("zwave:sensor:001", "Temp Sensor", true,
            channel("temperature", "Number:Temperature", "ZwaveSensor_Temp"));

        // Items for the unmapped Thing
        Map<String, OpenHabItemDto> itemLookup = Map.of(
            "ZwaveSensor_Temp", item("Number", "ZwaveSensor_Temp", "22.5"));

        sseClient.populateThingCaches(List.of(sensorThing), itemLookup);

        // thingDeviceCache should have the device
        assertThat(sseClient.getThingDevice("zwave:sensor:001")).isNotNull();
        assertThat(sseClient.getThingDevice("zwave:sensor:001").deviceId())
            .isEqualTo("zwave:sensor:001");

        // cachedDevices should include both Equipment and Thing devices
        var devices = sseClient.cachedDevices();
        assertThat(devices).hasSize(2);
        assertThat(devices).extracting(DeviceEntity::deviceId)
            .containsExactlyInAnyOrder("SwitchLiving", "zwave:sensor:001");

        // itemToThing should map the linked item
        assertThat(sseClient.getItemToThing("ZwaveSensor_Temp"))
            .isEqualTo("zwave:sensor:001");
    }

    // ---- 15. populateThingCaches: Equipment-backed Thing skipped ----

    @Test
    void populateThingCaches_equipmentBackedThingSkipped() {
        // Equipment with a toggle item
        sseClient.populateCaches(List.of(
            equipment("SwitchLiving", "Living Room Switch",
                List.of("Equipment", "PowerOutlet"),
                member("Switch", "SwitchLiving_Toggle", "ON",
                    List.of("Point", "Control", "Switch")))));

        // Thing whose linked item IS in the Equipment
        OpenHabThingDto equipmentThing = thing("zwave:switch:001", "ZWave Switch", true,
            channel("switch", "Switch", "SwitchLiving_Toggle"));

        // filterUnmapped should exclude this Thing
        Set<String> coveredItems = sseClient.buildCoveredItemsSet(List.of(
            equipment("SwitchLiving", "Living Room Switch",
                List.of("Equipment", "PowerOutlet"),
                member("Switch", "SwitchLiving_Toggle", "ON",
                    List.of("Point", "Control", "Switch")))));

        List<OpenHabThingDto> unmapped = sseClient.filterUnmapped(List.of(equipmentThing), coveredItems);
        assertThat(unmapped).isEmpty();

        // Even if we call populateThingCaches with an empty list, thingDeviceCache stays empty
        sseClient.populateThingCaches(unmapped, Map.of());
        assertThat(sseClient.getThingDevice("zwave:switch:001")).isNull();

        // cachedDevices only has Equipment devices
        assertThat(sseClient.cachedDevices()).hasSize(1);
    }

    // ---- 16. populateThingCaches: partial overlap treated as Equipment-backed ----

    @Test
    void populateThingCaches_partialOverlapTreatedAsEquipmentBacked() {
        // Equipment with toggle and temp items
        sseClient.populateCaches(List.of(
            equipment("ThermostatHall", "Hall Thermostat",
                List.of("Equipment", "HVAC"),
                member("Number", "ThermostatHall_CurrentTemp", "20.0",
                    List.of("Point", "Measurement", "Temperature")),
                member("Number", "ThermostatHall_TargetTemp", "21.0",
                    List.of("Point", "Setpoint", "Temperature")))));

        // Thing with TWO linked items: one in Equipment, one not
        OpenHabThingDto partialThing = thing("zwave:thermo:001", "ZWave Thermostat", true,
            channel("currentTemp", "Number:Temperature", "ThermostatHall_CurrentTemp"),
            channel("humidity", "Number:Humidity", "ZwaveThermo_Humidity"));

        Set<String> coveredItems = sseClient.buildCoveredItemsSet(List.of(
            equipment("ThermostatHall", "Hall Thermostat",
                List.of("Equipment", "HVAC"),
                member("Number", "ThermostatHall_CurrentTemp", "20.0",
                    List.of("Point", "Measurement", "Temperature")),
                member("Number", "ThermostatHall_TargetTemp", "21.0",
                    List.of("Point", "Setpoint", "Temperature")))));

        // Partial overlap → Equipment-backed → should NOT appear in unmapped
        List<OpenHabThingDto> unmapped = sseClient.filterUnmapped(List.of(partialThing), coveredItems);
        assertThat(unmapped).isEmpty();
    }

    // ---- 17. Thing OFFLINE overrides Equipment availability ----

    @Test
    void thingStatusOfflineOverridesEquipmentAvailability() {
        // Equipment device starts available
        sseClient.populateCaches(List.of(
            equipment("SwitchLiving", "Living Room Switch",
                List.of("Equipment", "PowerOutlet"),
                member("Switch", "SwitchLiving_Toggle", "ON",
                    List.of("Point", "Control", "Switch")))));

        var deviceBefore = sseClient.cachedDevices().stream()
            .filter(d -> d.deviceId().equals("SwitchLiving")).findFirst().orElseThrow();
        assertThat(deviceBefore.available()).isTrue();

        // Thing that backs this Equipment is OFFLINE
        OpenHabThingDto offlineThing = thing("zwave:switch:001", "ZWave Switch", false,
            channel("switch", "Switch", "SwitchLiving_Toggle"));

        Set<String> coveredItems = sseClient.buildCoveredItemsSet(List.of(
            equipment("SwitchLiving", "Living Room Switch",
                List.of("Equipment", "PowerOutlet"),
                member("Switch", "SwitchLiving_Toggle", "ON",
                    List.of("Point", "Control", "Switch")))));

        sseClient.buildThingIndexes(List.of(offlineThing), coveredItems);
        sseClient.enhanceAvailabilityFromThings(List.of(offlineThing), coveredItems);

        // Equipment device should now be unavailable
        var deviceAfter = sseClient.cachedDevices().stream()
            .filter(d -> d.deviceId().equals("SwitchLiving")).findFirst().orElseThrow();
        assertThat(deviceAfter.available()).isFalse();
    }

    // ---- 18. handleThingStatusEvent OFFLINE updates Thing device ----

    @Test
    void handleThingStatusEvent_offlineUpdatesThingDevice() throws Exception {
        // Set up an unmapped Thing device
        sseClient.populateCaches(List.of());  // No Equipment

        OpenHabThingDto sensorThing = thing("zwave:sensor:001", "Temp Sensor", true,
            channel("temperature", "Number:Temperature", "ZwaveSensor_Temp"));

        Map<String, OpenHabItemDto> itemLookup = Map.of(
            "ZwaveSensor_Temp", item("Number", "ZwaveSensor_Temp", "22.5"));

        sseClient.populateThingCaches(List.of(sensorThing), itemLookup);

        // Verify initially available
        assertThat(sseClient.getThingDevice("zwave:sensor:001").available()).isTrue();

        // SSE event: Thing goes OFFLINE
        String eventData = thingStatusEventJson("zwave:sensor:001", "OFFLINE", "ONLINE");
        sseClient.handleSseEvent(eventData);

        // Device should now be unavailable
        assertThat(sseClient.getThingDevice("zwave:sensor:001").available()).isFalse();
    }

    // ---- 19. handleThingStatusEvent ONLINE restores availability ----

    @Test
    void handleThingStatusEvent_onlineRestoresAvailability() throws Exception {
        // Set up an OFFLINE unmapped Thing device
        sseClient.populateCaches(List.of());

        OpenHabThingDto offlineThing = thing("zwave:sensor:001", "Temp Sensor", false,
            channel("temperature", "Number:Temperature", "ZwaveSensor_Temp"));

        Map<String, OpenHabItemDto> itemLookup = Map.of(
            "ZwaveSensor_Temp", item("Number", "ZwaveSensor_Temp", "22.5"));

        sseClient.populateThingCaches(List.of(offlineThing), itemLookup);

        // Verify initially unavailable
        assertThat(sseClient.getThingDevice("zwave:sensor:001").available()).isFalse();

        // SSE event: Thing comes ONLINE
        String eventData = thingStatusEventJson("zwave:sensor:001", "ONLINE", "OFFLINE");
        sseClient.handleSseEvent(eventData);

        // Device should now be available
        assertThat(sseClient.getThingDevice("zwave:sensor:001").available()).isTrue();
    }

    // ---- 20. handleThingStatusEvent unknown Thing ignored ----

    @Test
    void handleThingStatusEvent_unknownThingIgnored() throws Exception {
        sseClient.populateCaches(List.of());

        // SSE event for a Thing we know nothing about
        String eventData = thingStatusEventJson("zwave:unknown:999", "OFFLINE", "ONLINE");

        // Should not crash
        sseClient.handleSseEvent(eventData);

        // No devices affected
        assertThat(sseClient.cachedDevices()).isEmpty();
    }

    // ---- 21. SSE item event for Thing-linked item updates Thing device ----

    @Test
    void sseEventForThingLinkedItem_updatesThingDeviceCache() throws Exception {
        // Set up an unmapped Thing with a switch channel
        sseClient.populateCaches(List.of());

        OpenHabThingDto switchThing = thing("zwave:switch:002", "ZWave Plug", true,
            channel("switch", "Switch", "ZwavePlug_Switch"));

        Map<String, OpenHabItemDto> itemLookup = Map.of(
            "ZwavePlug_Switch", item("Switch", "ZwavePlug_Switch", "ON"));

        sseClient.populateThingCaches(List.of(switchThing), itemLookup);

        // Verify initially ON
        var deviceBefore = sseClient.getThingDevice("zwave:switch:002");
        assertThat(deviceBefore).isInstanceOf(SwitchDevice.class);
        assertThat(((SwitchDevice) deviceBefore).isOn()).isTrue();

        // SSE event: switch turns OFF
        var payload = new OpenHabStatePayloadDto("OnOff", "OFF", "OnOff", "ON");
        var payloadJson = objectMapper.writeValueAsString(payload);
        var event = new OpenHabSseEventDto(
            "openhab/items/ZwavePlug_Switch/statechanged", payloadJson, "ItemStateChangedEvent");
        String eventData = objectMapper.writeValueAsString(event);

        sseClient.handleSseEvent(eventData);

        // Verify device updated to OFF
        var deviceAfter = sseClient.getThingDevice("zwave:switch:002");
        assertThat(deviceAfter).isInstanceOf(SwitchDevice.class);
        assertThat(((SwitchDevice) deviceAfter).isOn()).isFalse();
    }

    // ========================================================================
    // Thing-Scoped Command Dispatch Resolution Tests
    // ========================================================================

    // ---- 23. resolveTargetItem: Thing-scoped switch finds Switch channel ----

    @Test
    void resolveTargetItem_thingScopedSwitch_findsSwitchChannel() {
        sseClient.populateCaches(List.of());

        OpenHabThingDto switchThing = thing("zwave:switch:003", "ZWave Plug", true,
            channel("switch", "Switch", "ZwavePlug_Switch"));

        Map<String, OpenHabItemDto> itemLookup = Map.of(
            "ZwavePlug_Switch", item("Switch", "ZwavePlug_Switch", "ON"));

        sseClient.populateThingCaches(List.of(switchThing), itemLookup);

        var command = DeviceCommand.turnOn("zwave:switch:003", Map.of(), "test", "corr-t1");
        String targetItem = sseClient.resolveTargetItem(command);

        assertThat(targetItem).isEqualTo("ZwavePlug_Switch");
    }

    // ---- 24. resolveTargetItem: Thing-scoped thermostat finds setpoint channel ----

    @Test
    void resolveTargetItem_thingScopedThermostat_findsSetpointChannel() {
        sseClient.populateCaches(List.of());

        OpenHabThingDto thermoThing = thing("zwave:thermo:001", "ZWave Thermostat", true,
            channel("currentTemp", "Number:Temperature", "ZwaveThermo_CurrentTemp"),
            channelWithType("setpointTemp", "zwave:setpoint_heating", "Number:Temperature", "ZwaveThermo_SetpointTemp"));

        Map<String, OpenHabItemDto> itemLookup = Map.of(
            "ZwaveThermo_CurrentTemp", item("Number", "ZwaveThermo_CurrentTemp", "20.0"),
            "ZwaveThermo_SetpointTemp", item("Number", "ZwaveThermo_SetpointTemp", "21.0"));

        sseClient.populateThingCaches(List.of(thermoThing), itemLookup);

        var command = DeviceCommand.setTemperature("zwave:thermo:001",
            new Temperature(new java.math.BigDecimal("22.0"), Temperature.TemperatureUnit.CELSIUS),
            "test", "corr-t2");
        String targetItem = sseClient.resolveTargetItem(command);

        assertThat(targetItem).isEqualTo("ZwaveThermo_SetpointTemp");
    }

    // ---- 25. resolveTargetItem: Thing-scoped light prefers Color over Switch ----

    @Test
    void resolveTargetItem_thingScopedLight_prefersColorOverSwitch() {
        sseClient.populateCaches(List.of());

        // Color channel listed AFTER Switch — order should not matter, Color wins by preference
        OpenHabThingDto lightThing = thing("hue:light:001", "Hue Bulb", true,
            channel("switch", "Switch", "HueBulb_Switch"),
            channel("color", "Color", "HueBulb_Color"));

        Map<String, OpenHabItemDto> itemLookup = Map.of(
            "HueBulb_Switch", item("Switch", "HueBulb_Switch", "ON"),
            "HueBulb_Color", item("Color", "HueBulb_Color", "120,100,80"));

        sseClient.populateThingCaches(List.of(lightThing), itemLookup);

        var command = DeviceCommand.turnOn("hue:light:001", Map.of(), "test", "corr-t3");
        String targetItem = sseClient.resolveTargetItem(command);

        assertThat(targetItem).isEqualTo("HueBulb_Color");
    }

    // ---- 26. resolveTargetItem: TRIGGER channel excluded from resolution ----

    @Test
    void resolveTargetItem_triggerChannelExcluded() {
        sseClient.populateCaches(List.of());

        // Thing with only a TRIGGER Switch channel — should not be resolved
        OpenHabThingDto triggerThing = thing("zwave:button:001", "Button", true,
            triggerChannel("button", "Switch", "ZwaveButton_Switch"));

        Map<String, OpenHabItemDto> itemLookup = Map.of(
            "ZwaveButton_Switch", item("Switch", "ZwaveButton_Switch", "ON"));

        sseClient.populateThingCaches(List.of(triggerThing), itemLookup);

        var command = DeviceCommand.turnOn("zwave:button:001", Map.of(), "test", "corr-t4");
        String targetItem = sseClient.resolveTargetItem(command);

        // Thing with trigger-only channels won't even resolve to a device,
        // so resolveTargetItem should return null (no entry in thingCache)
        assertThat(targetItem).isNull();
    }

    // ---- 27. resolveTargetItem: unknown Thing device returns null ----

    @Test
    void resolveTargetItem_unknownThingDevice_returnsNull() {
        sseClient.populateCaches(List.of());
        sseClient.populateThingCaches(List.of(), Map.of());

        var command = DeviceCommand.turnOn("zwave:nonexistent:999", Map.of(), "test", "corr-t5");
        String targetItem = sseClient.resolveTargetItem(command);

        assertThat(targetItem).isNull();
    }

    // ---- 22. Equipment item priority over Thing item ----

    @Test
    void equipmentItemPriorityOverThingItem() throws Exception {
        // Equipment with a toggle item
        sseClient.populateCaches(List.of(
            equipment("SwitchLiving", "Living Room Switch",
                List.of("Equipment", "PowerOutlet"),
                member("Switch", "SwitchLiving_Toggle", "ON",
                    List.of("Point", "Control", "Switch")))));

        // Also set up a Thing that links the SAME item (shouldn't happen in practice,
        // but tests the priority logic)
        // Note: we can't add to itemToThing for an item already in itemToEquipment,
        // because populateThingCaches would need the thing to be unmapped.
        // Instead, test that an SSE event for an Equipment item goes through Equipment path.

        // Simulate state change via Equipment path
        var payload = new OpenHabStatePayloadDto("OnOff", "OFF", "OnOff", "ON");
        var payloadJson = objectMapper.writeValueAsString(payload);
        var event = new OpenHabSseEventDto(
            "openhab/items/SwitchLiving_Toggle/statechanged", payloadJson, "ItemStateChangedEvent");
        String eventData = objectMapper.writeValueAsString(event);

        sseClient.handleSseEvent(eventData);

        // Equipment device should be updated (Equipment path used, not Thing path)
        var device = sseClient.cachedDevices().stream()
            .filter(d -> d.deviceId().equals("SwitchLiving")).findFirst().orElseThrow();
        assertThat(((SwitchDevice) device).isOn()).isFalse();

        // No Thing device should exist for this item
        assertThat(sseClient.getItemToThing("SwitchLiving_Toggle")).isNull();
    }
}
