package io.casehub.iot.webapp.app.service;

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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DefaultIoTDeviceApiStreamTest {

    private DefaultIoTDeviceApi api;
    private DeviceRegistry registry;

    @BeforeEach
    void setUp() {
        registry = mock(DeviceRegistry.class);

        api = new DefaultIoTDeviceApi();
        api.deviceRegistry = registry;
        api.principal = mock(CurrentPrincipal.class);
        api.historyProvider = mock(io.casehub.iot.api.spi.DeviceStateHistoryProvider.class);
        api.init();
    }

    @Test
    void streamShouldFilterEventsByClientTenancy() {
        when(registry.findAll()).thenReturn(List.of());

        var subscriber = api.streamDevices("tenant-A")
                .subscribe().withSubscriber(AssertSubscriber.create(10));

        api.onStateChange(stateChangeEvent(lightDevice("light-1", "tenant-A")));
        api.onStateChange(stateChangeEvent(lightDevice("light-2", "tenant-B")));

        var items = subscriber.getItems();
        assertThat(items).hasSize(2);
        assertThat(items.get(0).operation()).isEqualTo("snapshot");
        assertThat(items.get(1).operation()).isEqualTo("replace");
        assertThat(items.get(1).data()).hasSize(1);
        assertThat(items.get(1).data().get(0).deviceId()).isEqualTo("light-1");
    }

    @Test
    void twoClientsWithDifferentTenanciesSeeOnlyTheirEvents() {
        when(registry.findAll()).thenReturn(List.of());

        var subscriberA = api.streamDevices("tenant-A")
                .subscribe().withSubscriber(AssertSubscriber.create(10));

        var subscriberB = api.streamDevices("tenant-B")
                .subscribe().withSubscriber(AssertSubscriber.create(10));

        api.onStateChange(stateChangeEvent(lightDevice("light-A", "tenant-A")));
        api.onStateChange(stateChangeEvent(lightDevice("light-B", "tenant-B")));

        assertThat(subscriberA.getItems()).hasSize(2);
        assertThat(subscriberA.getItems().get(1).data().get(0).deviceId()).isEqualTo("light-A");

        assertThat(subscriberB.getItems()).hasSize(2);
        assertThat(subscriberB.getItems().get(1).data().get(0).deviceId()).isEqualTo("light-B");
    }

    @Test
    void snapshotShouldFilterByTenancy() {
        when(registry.findAll()).thenReturn(List.of(
                lightDevice("light-A", "tenant-A"),
                lightDevice("light-B", "tenant-B")
        ));

        var subscriber = api.streamDevices("tenant-A")
                .subscribe().withSubscriber(AssertSubscriber.create(1));

        var items = subscriber.getItems();
        assertThat(items).hasSize(1);
        assertThat(items.get(0).operation()).isEqualTo("snapshot");
        assertThat(items.get(0).data()).hasSize(1);
        assertThat(items.get(0).data().get(0).deviceId()).isEqualTo("light-A");
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
