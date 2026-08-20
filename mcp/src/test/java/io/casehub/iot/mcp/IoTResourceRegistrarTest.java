package io.casehub.iot.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.iot.api.DeviceClass;
import io.casehub.iot.api.DeviceEntity;
import io.casehub.iot.api.SwitchDevice;
import io.casehub.iot.api.spi.DeviceRegistry;
import io.casehub.platform.api.mcp.McpResourceContent;
import io.casehub.platform.api.mcp.McpResourceDescriptor;
import io.casehub.platform.api.mcp.McpResourceHandle;
import io.casehub.platform.api.mcp.McpResourceHandler;
import io.casehub.platform.api.mcp.McpResourceReadRequest;
import io.casehub.platform.api.mcp.McpResourceRegistration;
import io.casehub.platform.api.mcp.McpResourceRegistry;
import io.casehub.platform.api.mcp.TemplateResourceDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IoTResourceRegistrarTest {

    private ObjectMapper objectMapper;
    private TestDeviceRegistry deviceRegistry;
    private TestMcpResourceRegistry resourceRegistry;
    private IoTResourceRegistrar registrar;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        deviceRegistry = new TestDeviceRegistry();
        resourceRegistry = new TestMcpResourceRegistry();
        registrar = new IoTResourceRegistrar(resourceRegistry, deviceRegistry, objectMapper);
    }

    @Test
    void registerCreatesDeviceStateAndChangesResources() {
        registrar.register();

        assertThat(resourceRegistry.registrations).hasSize(2);

        var deviceState = resourceRegistry.registrations.get(0);
        assertThat(deviceState.descriptor().name()).isEqualTo("iot-device-state");
        assertThat(deviceState.descriptor()).isInstanceOf(TemplateResourceDescriptor.class);
        assertThat(((TemplateResourceDescriptor) deviceState.descriptor()).uriTemplate())
            .isEqualTo("iot://devices/{deviceId}/state");
        assertThat(deviceState.descriptor().subscribable()).isTrue();

        var changes = resourceRegistry.registrations.get(1);
        assertThat(changes.descriptor().name()).isEqualTo("iot-device-changes");
        assertThat(changes.descriptor().subscribable()).isTrue();
    }

    @Test
    void readDeviceStateReturnsDeviceJson() throws Exception {
        var now = Instant.now();
        deviceRegistry.devices = List.of(
            SwitchDevice.builder().deviceId("sw1").tenancyId("t1")
                .deviceClass(DeviceClass.SWITCH).lastUpdated(now)
                .providerId("ha").label("Switch").available(true)
                .on(true).build()
        );
        registrar.register();

        var handler = resourceRegistry.registrations.get(0).handler();
        var request = new McpResourceReadRequest("iot://devices/sw1/state", Map.of("deviceId", "sw1"));
        var content = handler.read(request);

        assertThat(content.uri()).isEqualTo("iot://devices/sw1/state");
        assertThat(content.text()).contains("sw1");
        assertThat(content.text()).contains("SWITCH");
    }

    @Test
    void readDeviceStateThrowsForUnknownDevice() {
        deviceRegistry.devices = List.of();
        registrar.register();

        var handler = resourceRegistry.registrations.get(0).handler();
        var request = new McpResourceReadRequest("iot://devices/unknown/state", Map.of("deviceId", "unknown"));

        assertThatThrownBy(() -> handler.read(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unknown");
    }

    @Test
    void notifyDeviceUpdateCallsHandle() {
        registrar.register();

        registrar.notifyDeviceUpdate("sw1");

        assertThat(resourceRegistry.registrations.get(0).handle.notifiedUris)
            .containsExactly("iot://devices/sw1/state");
    }

    @Test
    void notifyChangesUpdateCallsHandle() {
        registrar.register();

        registrar.notifyChangesUpdate();

        assertThat(resourceRegistry.registrations.get(1).handle.notifiedUris)
            .containsExactly("iot://devices/changes");
    }

    // ---- Test doubles ----

    static class TestDeviceRegistry implements DeviceRegistry {
        List<DeviceEntity> devices = List.of();
        @Override public Optional<DeviceEntity> findById(String id) {
            return devices.stream().filter(d -> d.deviceId().equals(id)).findFirst();
        }
        @Override public <T extends DeviceEntity> List<T> findByClass(Class<T> c) { return List.of(); }
        @Override public List<DeviceEntity> findByTenancyId(String t) { return List.of(); }
        @Override public List<DeviceEntity> findAll() { return devices; }
        @Override public void refresh() {}
        @Override public void refresh(String p) {}
    }

    static class TestMcpResourceRegistry implements McpResourceRegistry {
        final List<TestRegistration> registrations = new ArrayList<>();

        @Override
        public McpResourceRegistration newResource(McpResourceDescriptor descriptor) {
            var reg = new TestRegistration(descriptor);
            registrations.add(reg);
            return reg;
        }
        @Override public void deregister(String name) {}
        @Override public Optional<McpResourceDescriptor> resolve(String name) { return Optional.empty(); }
        @Override public List<McpResourceDescriptor> list() { return List.of(); }
    }

    static class TestRegistration implements McpResourceRegistration {
        final McpResourceDescriptor descriptor;
        McpResourceHandler handler;
        final TestHandle handle = new TestHandle();

        TestRegistration(McpResourceDescriptor descriptor) { this.descriptor = descriptor; }

        McpResourceDescriptor descriptor() { return descriptor; }
        McpResourceHandler handler() { return handler; }

        @Override public McpResourceRegistration handler(McpResourceHandler h) { this.handler = h; return this; }
        @Override public McpResourceRegistration completion(String argName, java.util.function.Supplier<List<String>> supplier) { return this; }
        @Override public McpResourceRegistration serverName(String name) { return this; }
        @Override public McpResourceHandle register() { return handle; }
    }

    static class TestHandle implements McpResourceHandle {
        final List<String> notifiedUris = new ArrayList<>();
        @Override public void notifyUpdate(String uri) { notifiedUris.add(uri); }
        @Override public void deregister() {}
    }
}
