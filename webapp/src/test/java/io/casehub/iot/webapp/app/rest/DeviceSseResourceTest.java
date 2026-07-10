package io.casehub.iot.webapp.app.rest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.casehub.iot.api.DeviceClass;
import io.casehub.iot.api.LightDevice;
import io.casehub.iot.api.StateChangeEvent;
import io.casehub.iot.api.spi.DeviceRegistry;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.smallrye.mutiny.helpers.test.AssertSubscriber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DeviceSseResourceTest {

    private DeviceSseResource resource;
    private DeviceRegistry registry;
    private CurrentPrincipal principal;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        registry = mock(DeviceRegistry.class);
        principal = mock(CurrentPrincipal.class);
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        resource = new DeviceSseResource();
        resource.deviceRegistry = registry;
        resource.principal = principal;
        resource.objectMapper = objectMapper;
        resource.init();
    }

    @Test
    void streamShouldFilterEventsByClientTenancy() {
        when(principal.tenancyId()).thenReturn("tenant-A");
        when(registry.findAll()).thenReturn(List.of());

        var subscriber = resource.stream()
                .subscribe().withSubscriber(AssertSubscriber.create(10));

        resource.onStateChange(stateChangeEvent(lightDevice("light-1", "tenant-A")));
        resource.onStateChange(stateChangeEvent(lightDevice("light-2", "tenant-B")));

        var items = subscriber.getItems();
        assertThat(items).hasSize(2);
        assertThat(items.get(1)).contains("light-1");
        assertThat(items.get(1)).doesNotContain("light-2");
    }

    @Test
    void onStateChangeShouldNotAccessPrincipal() {
        when(registry.findAll()).thenReturn(List.of());

        resource.onStateChange(stateChangeEvent(lightDevice("light-1", "tenant-A")));

        verifyNoInteractions(principal);
    }

    @Test
    void twoClientsWithDifferentTenanciesSeeOnlyTheirEvents() {
        var principalA = mock(CurrentPrincipal.class);
        when(principalA.tenancyId()).thenReturn("tenant-A");

        var principalB = mock(CurrentPrincipal.class);
        when(principalB.tenancyId()).thenReturn("tenant-B");

        when(registry.findAll()).thenReturn(List.of());

        resource.principal = principalA;
        var subscriberA = resource.stream()
                .subscribe().withSubscriber(AssertSubscriber.create(10));

        resource.principal = principalB;
        var subscriberB = resource.stream()
                .subscribe().withSubscriber(AssertSubscriber.create(10));

        resource.onStateChange(stateChangeEvent(lightDevice("light-A", "tenant-A")));
        resource.onStateChange(stateChangeEvent(lightDevice("light-B", "tenant-B")));

        assertThat(subscriberA.getItems()).hasSize(2);
        assertThat(subscriberA.getItems().get(1)).contains("light-A");

        assertThat(subscriberB.getItems()).hasSize(2);
        assertThat(subscriberB.getItems().get(1)).contains("light-B");
    }

    @Test
    void snapshotShouldFilterByTenancy() throws JsonProcessingException {
        when(principal.tenancyId()).thenReturn("tenant-A");
        when(registry.findAll()).thenReturn(List.of(
                lightDevice("light-A", "tenant-A"),
                lightDevice("light-B", "tenant-B")
        ));

        var subscriber = resource.stream()
                .subscribe().withSubscriber(AssertSubscriber.create(1));

        var items = subscriber.getItems();
        assertThat(items).hasSize(1);

        Map<String, Object> snapshot = objectMapper.readValue(items.get(0),
                new TypeReference<>() {});
        assertThat(snapshot.get("operation")).isEqualTo("snapshot");

        @SuppressWarnings("unchecked")
        var data = (List<Map<String, Object>>) snapshot.get("data");
        assertThat(data).hasSize(1);
        assertThat(data.get(0).get("deviceId")).isEqualTo("light-A");
    }

    private LightDevice lightDevice(String deviceId, String tenancyId) {
        return new LightDevice.Builder()
                .deviceId(deviceId)
                .label(deviceId)
                .available(true)
                .on(true)
                .deviceClass(DeviceClass.LIGHT)
                .providerId("test-provider")
                .tenancyId(tenancyId)
                .lastUpdated(Instant.now())
                .build();
    }

    private StateChangeEvent stateChangeEvent(LightDevice device) {
        return new StateChangeEvent(
                null,
                device,
                Set.of(),
                Instant.now(),
                "test-provider"
        );
    }
}
