package io.casehub.iot.openhab;

import io.casehub.iot.openhab.internal.OpenHabItemDto;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenHabLocationResolverTest {

    private OpenHabItemDto location(String name, String label, OpenHabItemDto... members) {
        return new OpenHabItemDto("Group", name, label, "NULL",
                List.of("Location"), List.of(members), null);
    }

    private OpenHabItemDto room(String name, String label, OpenHabItemDto... members) {
        return new OpenHabItemDto("Group", name, label, "NULL",
                List.of("Location", "Room"), List.of(members), null);
    }

    private OpenHabItemDto floor(String name, String label, OpenHabItemDto... members) {
        return new OpenHabItemDto("Group", name, label, "NULL",
                List.of("Location", "Floor"), List.of(members), null);
    }

    private OpenHabItemDto equipment(String name, String label) {
        return new OpenHabItemDto("Group", name, label, "NULL",
                List.of("Equipment", "HVAC"), null, null);
    }

    @Test
    void emptyItems_emptyMap() {
        Map<String, String> result = OpenHabLocationResolver.resolve(List.of());
        assertThat(result).isEmpty();
    }

    @Test
    void singleRoomWithEquipment_mapsToRoomLabel() {
        var thermostat = equipment("ThermostatLiving", "Living Thermostat");
        var livingRoom = room("LivingRoom", "Living Room", thermostat);

        Map<String, String> result = OpenHabLocationResolver.resolve(List.of(livingRoom));

        assertThat(result).containsEntry("ThermostatLiving", "Living Room");
    }

    @Test
    void nestedHierarchy_buildsFullPath() {
        var thermostat = equipment("ThermostatBedroom", "Bedroom Thermostat");
        var bedroom = room("Bedroom", "Bedroom", thermostat);
        var firstFloor = floor("Floor1", "First Floor", bedroom);
        var building = location("Home", "Home", firstFloor);

        Map<String, String> result = OpenHabLocationResolver.resolve(List.of(building));

        assertThat(result).containsEntry("ThermostatBedroom", "Home/First Floor/Bedroom");
    }

    @Test
    void multipleRoomsMultipleEquipment() {
        var light = equipment("LightKitchen", "Kitchen Light");
        var kitchen = room("Kitchen", "Kitchen", light);

        var therm = equipment("ThermostatLiving", "Living Thermostat");
        var living = room("LivingRoom", "Living Room", therm);

        Map<String, String> result = OpenHabLocationResolver.resolve(List.of(kitchen, living));

        assertThat(result)
                .containsEntry("LightKitchen", "Kitchen")
                .containsEntry("ThermostatLiving", "Living Room");
    }

    @Test
    void nonEquipmentNonLocationMembers_ignored() {
        var point = new OpenHabItemDto("Switch", "Light_Switch", "Light Switch", "ON",
                List.of("Point", "Control"), null, null);
        var room = room("LivingRoom", "Living Room", point);

        Map<String, String> result = OpenHabLocationResolver.resolve(List.of(room));

        assertThat(result).isEmpty();
    }

    @Test
    void equipmentDirectlyUnderBuilding_noRoom() {
        var sensor = equipment("OutdoorSensor", "Outdoor Sensor");
        var building = location("Home", "Home", sensor);

        Map<String, String> result = OpenHabLocationResolver.resolve(List.of(building));

        assertThat(result).containsEntry("OutdoorSensor", "Home");
    }

    @Test
    void nullMembers_handledGracefully() {
        var room = new OpenHabItemDto("Group", "EmptyRoom", "Empty Room", "NULL",
                List.of("Location", "Room"), null, null);

        Map<String, String> result = OpenHabLocationResolver.resolve(List.of(room));

        assertThat(result).isEmpty();
    }
}
