package io.casehub.iot.homeassistant;

import io.casehub.iot.homeassistant.internal.HaAreaDto;
import io.casehub.iot.homeassistant.internal.HaDeviceRegistryDto;
import io.casehub.iot.homeassistant.internal.HaEntityRegistryDto;
import io.casehub.iot.homeassistant.internal.HaFloorDto;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HomeAssistantLocationResolverTest {

    @Test
    void entityWithDirectArea_mapsToAreaName() {
        var areas = List.of(new HaAreaDto("living_room", "Living Room", null));
        var floors = List.<HaFloorDto>of();
        var entities = List.of(new HaEntityRegistryDto("light.living", "living_room", "dev1"));
        var devices = List.<HaDeviceRegistryDto>of();

        Map<String, String> result = HomeAssistantLocationResolver.resolve(areas, floors, entities, devices, null);

        assertThat(result).containsEntry("light.living", "living-room");
    }

    @Test
    void entityWithAreaAndFloor_includesFloorInPath() {
        var areas = List.of(new HaAreaDto("bedroom", "Bedroom", "first_floor"));
        var floors = List.of(new HaFloorDto("first_floor", "First Floor"));
        var entities = List.of(new HaEntityRegistryDto("light.bedroom", "bedroom", null));
        var devices = List.<HaDeviceRegistryDto>of();

        Map<String, String> result = HomeAssistantLocationResolver.resolve(areas, floors, entities, devices, null);

        assertThat(result).containsEntry("light.bedroom", "first-floor/bedroom");
    }

    @Test
    void entityWithNoAreaButDeviceHasArea_inheritsDeviceArea() {
        var areas = List.of(new HaAreaDto("kitchen", "Kitchen", null));
        var floors = List.<HaFloorDto>of();
        var entities = List.of(new HaEntityRegistryDto("sensor.temp", null, "dev1"));
        var devices = List.of(new HaDeviceRegistryDto("dev1", "kitchen"));

        Map<String, String> result = HomeAssistantLocationResolver.resolve(areas, floors, entities, devices, null);

        assertThat(result).containsEntry("sensor.temp", "kitchen");
    }

    @Test
    void entityWithNoAreaNoDevice_notInMap() {
        var areas = List.of(new HaAreaDto("kitchen", "Kitchen", null));
        var floors = List.<HaFloorDto>of();
        var entities = List.of(new HaEntityRegistryDto("sensor.orphan", null, null));
        var devices = List.<HaDeviceRegistryDto>of();

        Map<String, String> result = HomeAssistantLocationResolver.resolve(areas, floors, entities, devices, null);

        assertThat(result).doesNotContainKey("sensor.orphan");
    }

    @Test
    void locationPrefix_prependedToPath() {
        var areas = List.of(new HaAreaDto("kitchen", "Kitchen", null));
        var floors = List.<HaFloorDto>of();
        var entities = List.of(new HaEntityRegistryDto("light.kitchen", "kitchen", null));
        var devices = List.<HaDeviceRegistryDto>of();

        Map<String, String> result = HomeAssistantLocationResolver.resolve(
                areas, floors, entities, devices, "home/ground-floor");

        assertThat(result).containsEntry("light.kitchen", "home/ground-floor/kitchen");
    }

    @Test
    void locationPrefixWithFloor_fullHierarchy() {
        var areas = List.of(new HaAreaDto("bedroom", "Master Bedroom", "upstairs"));
        var floors = List.of(new HaFloorDto("upstairs", "Upstairs"));
        var entities = List.of(new HaEntityRegistryDto("climate.bedroom", "bedroom", null));
        var devices = List.<HaDeviceRegistryDto>of();

        Map<String, String> result = HomeAssistantLocationResolver.resolve(
                areas, floors, entities, devices, "home");

        assertThat(result).containsEntry("climate.bedroom", "home/upstairs/master-bedroom");
    }

    @Test
    void multipleEntities_allMapped() {
        var areas = List.of(
                new HaAreaDto("kitchen", "Kitchen", null),
                new HaAreaDto("living", "Living Room", null));
        var floors = List.<HaFloorDto>of();
        var entities = List.of(
                new HaEntityRegistryDto("light.kitchen", "kitchen", null),
                new HaEntityRegistryDto("switch.living", "living", null));
        var devices = List.<HaDeviceRegistryDto>of();

        Map<String, String> result = HomeAssistantLocationResolver.resolve(areas, floors, entities, devices, null);

        assertThat(result)
                .containsEntry("light.kitchen", "kitchen")
                .containsEntry("switch.living", "living-room");
    }

    @Test
    void emptyInputs_emptyMap() {
        Map<String, String> result = HomeAssistantLocationResolver.resolve(
                List.of(), List.of(), List.of(), List.of(), null);

        assertThat(result).isEmpty();
    }

    @Test
    void areaNameSlugified_spacesBecomeDashes_lowercased() {
        var areas = List.of(new HaAreaDto("lr", "Living  Room", null));
        var floors = List.<HaFloorDto>of();
        var entities = List.of(new HaEntityRegistryDto("light.lr", "lr", null));
        var devices = List.<HaDeviceRegistryDto>of();

        Map<String, String> result = HomeAssistantLocationResolver.resolve(areas, floors, entities, devices, null);

        assertThat(result).containsEntry("light.lr", "living-room");
    }
}
