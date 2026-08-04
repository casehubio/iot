package io.casehub.iot.webapp.app.persistence;

import io.casehub.iot.api.spi.DeviceStateHistoryProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

@ApplicationScoped
public class JpaDeviceStateHistoryProvider implements DeviceStateHistoryProvider {

    @Inject
    EntityManager em;

    @Override
    public List<HistoryEntry> findHistory(String deviceId, String tenancyId, Instant from, Instant to, int limit) {
        var query = em.createQuery(
                """
                SELECT h FROM IoTDeviceStateHistoryEntity h
                WHERE h.deviceId = :deviceId
                  AND h.tenancyId = :tenancyId
                  AND (:from IS NULL OR h.occurredAt >= :from)
                  AND (:to IS NULL OR h.occurredAt <= :to)
                ORDER BY h.occurredAt DESC
                """,
                IoTDeviceStateHistoryEntity.class
                                  );

        query.setParameter("deviceId", deviceId);
        query.setParameter("tenancyId", tenancyId);
        query.setParameter("from", from);
        query.setParameter("to", to);
        query.setMaxResults(limit);

        return query.getResultList().stream()
                    .map(h -> new HistoryEntry(
                            h.getDeviceId(),
                            h.getDeviceClass(),
                            h.getStateSnapshot(),
                            Arrays.asList(h.getChangedCapabilities()),
                            h.getOccurredAt()
                    ))
                    .toList();
    }
}
