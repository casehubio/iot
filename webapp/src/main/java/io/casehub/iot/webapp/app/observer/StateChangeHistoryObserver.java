package io.casehub.iot.webapp.app.observer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.iot.api.StateChangeEvent;
import io.casehub.iot.webapp.app.persistence.IoTDeviceStateHistoryEntity;
import io.casehub.platform.api.identity.CurrentPrincipal;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.logging.Logger;

@ApplicationScoped
public class StateChangeHistoryObserver {

    private static final Logger LOG = Logger.getLogger(StateChangeHistoryObserver.class.getName());

    private final EntityManager entityManager;
    private final CurrentPrincipal currentPrincipal;
    private final ObjectMapper objectMapper;

    @Inject
    StateChangeHistoryObserver(final EntityManager entityManager,
                               final CurrentPrincipal currentPrincipal,
                               final ObjectMapper objectMapper) {
        this.entityManager = entityManager;
        this.currentPrincipal = currentPrincipal;
        this.objectMapper = objectMapper;
    }

    @Transactional
    void onStateChange(@ObservesAsync final StateChangeEvent event) {
        try {
            final var entity = new IoTDeviceStateHistoryEntity(
                currentPrincipal.tenancyId(),
                event.after().getDeviceId(),
                event.providerId(),
                event.after().deviceClass().name(),
                event.after(),
                event.changedCapabilities().toArray(new String[0]),
                event.occurredAt()
            );

            entityManager.persist(entity);

            LOG.fine(() -> String.format(
                "Persisted state change for device %s: %d capabilities changed",
                event.after().getDeviceId(),
                event.changedCapabilities().size()
            ));
        } catch (Exception e) {
            LOG.severe("Failed to persist state change for device "
                + event.after().getDeviceId() + ": " + e.getMessage());
            throw e;
        }
    }
}
