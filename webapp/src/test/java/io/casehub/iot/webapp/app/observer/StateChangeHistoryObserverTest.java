package io.casehub.iot.webapp.app.observer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.iot.api.DeviceClass;
import io.casehub.iot.api.Light;
import io.casehub.iot.api.StateChangeEvent;
import io.casehub.iot.webapp.app.persistence.IoTDeviceStateHistoryEntity;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class StateChangeHistoryObserverTest {

    @Inject
    StateChangeHistoryObserver observer;

    @Inject
    EntityManager entityManager;

    @Inject
    CurrentPrincipal currentPrincipal;

    @Inject
    ObjectMapper objectMapper;

    @Test
    @Transactional
    void shouldPersistStateChangeEvent() {
        // Given
        final Light before = new Light("light-1", "Living Room Light", true, true);
        final Light after = before.withOnOff(false);

        final StateChangeEvent event = new StateChangeEvent(
            before,
            after,
            Set.of("onOff"),
            Instant.now(),
            "test-provider"
        );

        // When
        observer.onStateChange(event);
        entityManager.flush();
        entityManager.clear();

        // Then
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
        assertThat(persisted.getChangedCapabilities()).containsExactly("onOff");
        assertThat(persisted.getOccurredAt()).isEqualTo(event.occurredAt());

        // Verify state snapshot deserialization
        final Light snapshot = (Light) persisted.getStateSnapshot();
        assertThat(snapshot.getDeviceId()).isEqualTo("light-1");
        assertThat(snapshot.isOnOff()).isFalse();
    }

    @Test
    @Transactional
    void shouldHandleMultipleCapabilityChanges() {
        // Given
        final Light before = new Light("light-2", "Kitchen Light", true, true)
            .withBrightness(50);
        final Light after = before
            .withOnOff(false)
            .withBrightness(0);

        final StateChangeEvent event = new StateChangeEvent(
            before,
            after,
            Set.of("onOff", "brightness"),
            Instant.now(),
            "test-provider"
        );

        // When
        observer.onStateChange(event);
        entityManager.flush();

        // Then
        final IoTDeviceStateHistoryEntity persisted = entityManager
            .createQuery(
                "SELECT h FROM IoTDeviceStateHistoryEntity h WHERE h.deviceId = :deviceId",
                IoTDeviceStateHistoryEntity.class
            )
            .setParameter("deviceId", "light-2")
            .getSingleResult();

        assertThat(persisted.getChangedCapabilities())
            .containsExactlyInAnyOrder("onOff", "brightness");
    }

    @Test
    @Transactional
    void shouldHandleNewDeviceWithNoBefore() {
        // Given - new device has no "before" state
        final Light after = new Light("light-3", "New Light", true, true);

        final StateChangeEvent event = new StateChangeEvent(
            null,
            after,
            Set.of(),
            Instant.now(),
            "test-provider"
        );

        // When
        observer.onStateChange(event);
        entityManager.flush();

        // Then
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
