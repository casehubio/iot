package io.casehub.iot.bridge.persistence.jpa;

import io.casehub.iot.api.bridge.BridgeAuditEvent;
import io.casehub.iot.api.bridge.BridgeAuditEventType;
import io.casehub.iot.api.bridge.BridgeAuditQuery;
import io.casehub.iot.api.bridge.BridgeAuditStore;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@QuarkusTestResource(value = PostgresTestResource.class, restrictToAnnotatedClass = true)
class JpaAuditRetentionDisabledTest {

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
    void purgeIsNoOpWhenRetentionNotConfigured() {
        final Instant twoDaysAgo = Instant.now().minus(2, ChronoUnit.DAYS);
        store.save(new BridgeAuditEvent("t", twoDaysAgo, BridgeAuditEventType.STATE_CHANGE,
            null, "old-device", null));

        retentionJob.purge();

        final var remaining = store.query(BridgeAuditQuery.builder().build());
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0).deviceId()).isEqualTo("old-device");
    }
}
