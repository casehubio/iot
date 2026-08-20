package io.casehub.iot.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.casehub.iot.api.DeviceClass;
import io.casehub.iot.api.StateChangeEvent;
import io.casehub.iot.api.SwitchDevice;
import io.casehub.platform.api.mcp.McpResourceContent;
import io.casehub.platform.api.mcp.McpResourceReadRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class IoTStateChangeResourceObserverTest {

    private IoTResourceRegistrarTest.TestMcpResourceRegistry resourceRegistry;
    private IoTResourceRegistrar registrar;
    private IoTStateChangeResourceObserver observer;

    @BeforeEach
    void setUp() {
        var objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        var deviceRegistry = new IoTResourceRegistrarTest.TestDeviceRegistry();
        resourceRegistry = new IoTResourceRegistrarTest.TestMcpResourceRegistry();
        registrar = new IoTResourceRegistrar(resourceRegistry, deviceRegistry, objectMapper);
        registrar.register();
        observer = new IoTStateChangeResourceObserver();
        observer.registrar = registrar;
    }

    @Test
    void stateChangeNotifiesBothResources() {
        var event = createEvent("sw1");

        observer.onStateChange(event);

        var deviceHandle = resourceRegistry.registrations.get(0).handle;
        assertThat(deviceHandle.notifiedUris).containsExactly("iot://devices/sw1/state");

        var changesHandle = resourceRegistry.registrations.get(1).handle;
        assertThat(changesHandle.notifiedUris).containsExactly("iot://devices/changes");
    }

    @Test
    void ringBufferBoundedAtMax() {
        for (int i = 0; i < 60; i++) {
            observer.onStateChange(createEvent("dev-" + i));
        }

        // Read the changes resource
        var handler = resourceRegistry.registrations.get(1).handler();
        try {
            var content = handler.read(McpResourceReadRequest.of("iot://devices/changes"));
            // Should contain at most 50 entries
            var text = content.text();
            // Count occurrences of "deviceId" to verify bounded size
            long count = text.chars().filter(c -> c == '{').count();
            assertThat(count).isLessThanOrEqualTo(50);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void multipleEventsForSameDeviceNotifyCorrectly() {
        observer.onStateChange(createEvent("sw1"));
        observer.onStateChange(createEvent("sw1"));

        var deviceHandle = resourceRegistry.registrations.get(0).handle;
        assertThat(deviceHandle.notifiedUris)
            .containsExactly("iot://devices/sw1/state", "iot://devices/sw1/state");
    }

    private StateChangeEvent createEvent(String deviceId) {
        var now = Instant.now();
        var before = SwitchDevice.builder().deviceId(deviceId).tenancyId("t1")
            .deviceClass(DeviceClass.SWITCH).lastUpdated(now)
            .providerId("ha").label("Switch").available(true)
            .on(false).build();
        var after = SwitchDevice.builder().deviceId(deviceId).tenancyId("t1")
            .deviceClass(DeviceClass.SWITCH).lastUpdated(now)
            .providerId("ha").label("Switch").available(true)
            .on(true).build();
        return new StateChangeEvent(before, after, Set.of("on"), now, "ha");
    }
}
