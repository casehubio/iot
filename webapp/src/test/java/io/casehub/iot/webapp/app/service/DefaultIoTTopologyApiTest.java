package io.casehub.iot.webapp.app.service;

import io.casehub.iot.api.DeviceClass;
import io.casehub.iot.api.LightDevice;
import io.casehub.iot.api.ThermostatDevice;
import io.casehub.iot.testing.MockDeviceRegistry;
import io.casehub.iot.webapp.rest.DriftStatus;
import io.casehub.iot.webapp.rest.TopologyResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultIoTTopologyApiTest {

    private MockDeviceRegistry registry;
    private TopologyAssembler assembler;
    private DefaultIoTTopologyApi api;

    @BeforeEach
    void setUp() {
        registry = new MockDeviceRegistry();
        assembler = new TopologyAssembler(registry);
        api = new DefaultIoTTopologyApi();
        api.assembler = assembler;
        api.broadcaster = io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor.create();
    }

    private LightDevice.Builder lightBuilder(String id, String tenant) {
        return new LightDevice.Builder()
                .deviceId(id).deviceClass(DeviceClass.LIGHT)
                .label("Light " + id).available(true)
                .lastUpdated(Instant.EPOCH).tenancyId(tenant)
                .providerId("test").on(false);
    }

    @Test
    void getTopology_returns_all_devices_for_tenant() {
        registry.addDevice(lightBuilder("l1", "t1").location("HQ/Room1").build());
        registry.addDevice(new ThermostatDevice.Builder()
                .deviceId("th1").deviceClass(DeviceClass.THERMOSTAT)
                .label("Thermostat").available(true).lastUpdated(Instant.EPOCH)
                .tenancyId("t1").providerId("test")
                .currentTemperature(new io.casehub.iot.api.Temperature(
                        new BigDecimal("21"), io.casehub.iot.api.Temperature.TemperatureUnit.CELSIUS))
                .targetTemperature(new io.casehub.iot.api.Temperature(
                        new BigDecimal("22"), io.casehub.iot.api.Temperature.TemperatureUnit.CELSIUS))
                .mode(io.casehub.iot.api.ThermostatMode.HEAT)
                .location("HQ/Room2").build());
        registry.addDevice(lightBuilder("l2", "t2").build());

        TopologyResponse response = api.getTopology("t1");

        assertEquals(2, response.nodes().size());
        assertTrue(response.nodes().stream().allMatch(n -> n.driftStatus() == DriftStatus.UNMONITORED));
    }

    @Test
    void getTopology_includes_location_aggregates() {
        registry.addDevice(lightBuilder("l1", "t1").location("HQ/Floor1").build());
        registry.addDevice(lightBuilder("l2", "t1").location("HQ/Floor1").build());

        TopologyResponse response = api.getTopology("t1");

        assertNotNull(response.locationAggregates().get("HQ"));
        assertEquals(2, response.locationAggregates().get("HQ").total());
    }

    @Test
    void streamTopology_filters_broadcast_by_tenancyId() {
        registry.addDevice(lightBuilder("l1", "t1").location("HQ/Room1").build());

        var events = new ArrayList<TopologyStreamEvent>();
        api.streamTopology("t1")
           .subscribe().with(events::add);

        // Broadcast an event for tenant t1 — should arrive
        var node = assembler.reassembleNode("l1", "t1");
        api.broadcaster.onNext(new TopologyStreamEvent("t1", "update", List.of(node)));

        // Broadcast an event for tenant t2 — should be filtered out
        registry.addDevice(lightBuilder("l2", "t2").location("Other").build());
        var otherNode = assembler.reassembleNode("l2", "t2");
        api.broadcaster.onNext(new TopologyStreamEvent("t2", "update", List.of(otherNode)));

        // snapshot + 1 matching update = 2 events
        assertEquals(2, events.size());
        assertEquals("snapshot", events.get(0).operation());
        assertEquals("update", events.get(1).operation());
    }
}
