package io.casehub.iot.webapp.app.observer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.iot.api.DeviceClass;
import io.casehub.iot.api.LightDevice;
import io.casehub.iot.api.StateChangeEvent;
import io.casehub.iot.webapp.app.persistence.IoTDeviceStateHistoryEntity;
import io.casehub.iot.webapp.app.WebappPostgresTestResource;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@QuarkusTestResource(WebappPostgresTestResource.class)
class StateChangeHistoryObserverTest {

    @Inject
    StateChangeHistoryObserver observer;

    @Inject
    EntityManager entityManager;

    @Inject
    CurrentPrincipal currentPrincipal;

    @Inject
    ObjectMapper objectMapper;

    private LightDevice light(String deviceId, String label, boolean available, boolean on) {
        return new LightDevice.Builder()
                .deviceId(deviceId)
                .label(label)
                .available(available)
                .on(on)
                .deviceClass(DeviceClass.LIGHT)
                .providerId("test-provider")
                .tenancyId("test-tenant")
                .lastUpdated(Instant.now())
                .build();
    }

    @Test
    @Transactional
    void shouldPersistStateChangeEvent() {
        final LightDevice before = light("light-1", "Living Room Light", true, true);
        final LightDevice after = light("light-1", "Living Room Light", true, false);

        final StateChangeEvent event = new StateChangeEvent(
            before,
            after,
            Set.of("isOn"),
            Instant.now().truncatedTo(ChronoUnit.MICROS),
            "test-provider"
        );

        observer.onStateChange(event);
        entityManager.flush();
        entityManager.clear();

        final IoTDeviceStateHistoryEntity persisted = entityManager
            .createQuery(
                "SELECT h FROM IoTDeviceStateHistoryEntity h WHERE h.deviceId = :deviceId",
                IoTDeviceStateHistoryEntity.class
            )
            .setParameter("deviceId", "light-1")
            .getSingleResult();

        assertThat(persisted).isNotNull();
        assertThat(persisted.getTenancyId()).isEqualTo(currentPrincipal.tenancyId());
        assertThat(persisted.getDeviceId()).isEqualTo("light-1");
        assertThat(persisted.getProviderId()).isEqualTo("test-provider");
        assertThat(persisted.getDeviceClass()).isEqualTo(DeviceClass.LIGHT.name());
        assertThat(persisted.getChangedCapabilities()).containsExactly("isOn");
        assertThat(persisted.getOccurredAt()).isEqualTo(event.occurredAt());

        final LightDevice snapshot = (LightDevice) persisted.getStateSnapshot();
        assertThat(snapshot.deviceId()).isEqualTo("light-1");
        assertThat(snapshot.isOn()).isFalse();
    }

    @Test
    @Transactional
    void shouldHandleMultipleCapabilityChanges() {
        final LightDevice before = new LightDevice.Builder()
                .deviceId("light-2").label("Kitchen Light").available(true)
                .on(true).brightness(50)
                .deviceClass(DeviceClass.LIGHT).providerId("test-provider").tenancyId("test-tenant")
                .lastUpdated(Instant.now()).build();
        final LightDevice after = new LightDevice.Builder()
                .deviceId("light-2").label("Kitchen Light").available(true)
                .on(false).brightness(0)
                .deviceClass(DeviceClass.LIGHT).providerId("test-provider").tenancyId("test-tenant")
                .lastUpdated(Instant.now()).build();

        final StateChangeEvent event = new StateChangeEvent(
            before,
            after,
            Set.of("isOn", "brightness"),
            Instant.now().truncatedTo(ChronoUnit.MICROS),
            "test-provider"
        );

        observer.onStateChange(event);
        entityManager.flush();

        final IoTDeviceStateHistoryEntity persisted = entityManager
            .createQuery(
                "SELECT h FROM IoTDeviceStateHistoryEntity h WHERE h.deviceId = :deviceId",
                IoTDeviceStateHistoryEntity.class
            )
            .setParameter("deviceId", "light-2")
            .getSingleResult();

        assertThat(persisted.getChangedCapabilities())
            .containsExactlyInAnyOrder("isOn", "brightness");
    }

    @Test
    @Transactional
    void shouldHandleNewDeviceWithNoBefore() {
        final LightDevice after = light("light-3", "New Light", true, true);

        final StateChangeEvent event = new StateChangeEvent(
            null,
            after,
            Set.of(),
            Instant.now().truncatedTo(ChronoUnit.MICROS),
            "test-provider"
        );

        observer.onStateChange(event);
        entityManager.flush();

        final IoTDeviceStateHistoryEntity persisted = entityManager
            .createQuery(
                "SELECT h FROM IoTDeviceStateHistoryEntity h WHERE h.deviceId = :deviceId",
                IoTDeviceStateHistoryEntity.class
            )
            .setParameter("deviceId", "light-3")
            .getSingleResult();

        assertThat(persisted).isNotNull();
        assertThat(persisted.getChangedCapabilities()).isEmpty();
    }
}
