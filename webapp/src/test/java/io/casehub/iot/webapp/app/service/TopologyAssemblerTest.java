package io.casehub.iot.webapp.app.service;

import io.casehub.iot.api.DeviceClass;
import io.casehub.iot.api.LightDevice;
import io.casehub.iot.testing.MockDeviceRegistry;
import io.casehub.iot.webapp.rest.DriftStatus;
import io.casehub.iot.webapp.rest.TopologyResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TopologyAssemblerTest {

    private MockDeviceRegistry registry;
    private TopologyAssembler assembler;

    @BeforeEach
    void setUp() {
        registry = new MockDeviceRegistry();
        assembler = new TopologyAssembler(registry);
    }

    private LightDevice.Builder lightBuilder(String id, String tenant) {
        return new LightDevice.Builder()
                .deviceId(id).deviceClass(DeviceClass.LIGHT)
                .label("Light " + id).available(true)
                .lastUpdated(Instant.EPOCH).tenancyId(tenant)
                .providerId("test").on(false);
    }

    @Test
    void locationPath_parsed_from_slash_separated_string() {
        registry.addDevice(lightBuilder("l1", "t1").location("HQ/Floor2/Kitchen").build());
        TopologyResponse response = assembler.assemble("t1");
        assertEquals(1, response.nodes().size());
        assertEquals(List.of("HQ", "Floor2", "Kitchen"),
                response.nodes().getFirst().locationPath());
    }

    @Test
    void locationPath_null_produces_empty_list() {
        registry.addDevice(lightBuilder("l2", "t1").build());
        TopologyResponse response = assembler.assemble("t1");
        assertEquals(1, response.nodes().size());
        assertTrue(response.nodes().getFirst().locationPath().isEmpty());
    }

    @Test
    void locationPath_single_segment_produces_single_element_list() {
        registry.addDevice(lightBuilder("l3", "t1").location("Kitchen").build());
        TopologyResponse response = assembler.assemble("t1");
        assertEquals(List.of("Kitchen"),
                response.nodes().getFirst().locationPath());
    }

    @Test
    void devices_without_desired_state_are_unmonitored() {
        registry.addDevice(lightBuilder("l4", "t1").build());
        TopologyResponse response = assembler.assemble("t1");
        assertEquals(DriftStatus.UNMONITORED,
                response.nodes().getFirst().driftStatus());
    }

    @Test
    void tenancy_filtering_applied() {
        registry.addDevice(lightBuilder("la", "t1").build());
        registry.addDevice(lightBuilder("lb", "t2").build());
        TopologyResponse response = assembler.assemble("t1");
        assertEquals(1, response.nodes().size());
        assertEquals("la", response.nodes().getFirst().deviceId());
    }

    @Test
    void no_edges_without_desired_state() {
        registry.addDevice(lightBuilder("l5", "t1").build());
        TopologyResponse response = assembler.assemble("t1");
        assertTrue(response.edges().isEmpty());
    }

    @Test
    void locationAggregates_computed_per_subtree() {
        registry.addDevice(lightBuilder("l1", "t1").location("HQ/Floor1").build());
        registry.addDevice(lightBuilder("l2", "t1").location("HQ/Floor1").build());
        registry.addDevice(lightBuilder("l3", "t1").location("HQ/Floor2").build());
        TopologyResponse response = assembler.assemble("t1");
        var hqAggregate = response.locationAggregates().get("HQ");
        assertNotNull(hqAggregate);
        assertEquals(3, hqAggregate.total());
        var floor1 = response.locationAggregates().get("HQ/Floor1");
        assertNotNull(floor1);
        assertEquals(2, floor1.total());
    }
}
