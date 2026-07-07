package io.casehub.iot.bridge.persistence.jpa;

import io.casehub.iot.api.bridge.BridgeAuditEvent;
import io.casehub.iot.api.bridge.BridgeAuditEventType;
import io.casehub.iot.api.bridge.BridgeAuditQuery;
import io.casehub.iot.api.bridge.BridgeAuditStore;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@TestProfile(JpaAuditRetentionJobTest.RetentionEnabledProfile.class)
@QuarkusTestResource(value = PostgresTestResource.class, restrictToAnnotatedClass = true)
class JpaAuditRetentionJobTest {

    @Inject
    BridgeAuditStore store;

    @Inject
    JpaAuditRetentionJob retentionJob;

    @Inject
    EntityManager em;

    @BeforeEach
    @Transactional
    void cleanTable() {
        em.createQuery("DELETE FROM BridgeAuditJpaEntity").executeUpdate();
    }

    @Test
    void purgeDeletesEventsOlderThanRetentionPeriod() {
        final Instant now = Instant.now();
        final Instant twoDaysAgo = now.minus(2, ChronoUnit.DAYS);
        final Instant threeHoursAgo = now.minus(3, ChronoUnit.HOURS);

        store.save(new BridgeAuditEvent("t", twoDaysAgo, BridgeAuditEventType.STATE_CHANGE,
            null, "old-device", null));
        store.save(new BridgeAuditEvent("t", threeHoursAgo, BridgeAuditEventType.STATE_CHANGE,
            null, "recent-device", null));

        retentionJob.purge();

        final var remaining = store.query(BridgeAuditQuery.builder().build());
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0).deviceId()).isEqualTo("recent-device");
    }

    @Test
    void purgePreservesEventsWithinRetentionPeriod() {
        final Instant now = Instant.now();
        store.save(new BridgeAuditEvent("t", now.minus(12, ChronoUnit.HOURS), BridgeAuditEventType.STATE_CHANGE,
            null, "d1", null));
        store.save(new BridgeAuditEvent("t", now.minus(6, ChronoUnit.HOURS), BridgeAuditEventType.COMMAND_SENT,
            "corr-1", "d2", null));

        retentionJob.purge();

        final var remaining = store.query(BridgeAuditQuery.builder().build());
        assertThat(remaining).hasSize(2);
    }

    public static class RetentionEnabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("casehub.iot.bridge.audit-store.jpa.retention-days", "1");
        }
    }
}
