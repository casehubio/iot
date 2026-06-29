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
class PostgresDialectTest {

    @Inject
    BridgeAuditStore store;

    @Inject
    EntityManager em;

    @BeforeEach
    @Transactional
    void cleanTable() {
        em.createQuery("DELETE FROM BridgeAuditJpaEntity").executeUpdate();
    }

    @Test
    void saveAndQueryRoundTrip() {
        final var event = new BridgeAuditEvent(
            "tenant-1", Instant.now(), BridgeAuditEventType.STATE_CHANGE,
            "corr-1", "light.kitchen", null);
        store.save(event);

        final var results = store.query(BridgeAuditQuery.builder().tenancyId("tenant-1").build());
        assertThat(results).hasSize(1);
        assertThat(results.get(0).tenancyId()).isEqualTo("tenant-1");
        assertThat(results.get(0).deviceId()).isEqualTo("light.kitchen");
    }

    @Test
    void queryRespectsOffset() {
        final Instant base = Instant.parse("2026-06-29T10:00:00Z");
        for (int i = 0; i < 10; i++) {
            store.save(new BridgeAuditEvent(
                "t", base.plus(i, ChronoUnit.MINUTES), BridgeAuditEventType.STATE_CHANGE,
                null, "d" + i, null));
        }

        final var results = store.query(BridgeAuditQuery.builder().offset(3).limit(3).build());
        assertThat(results).extracting(BridgeAuditEvent::deviceId)
            .containsExactly("d6", "d5", "d4");
    }

    @Test
    void jsonbMessageRoundTrip() {
        final var message = new io.casehub.iot.api.bridge.BridgeMessage.Heartbeat("tenant-1", Instant.now());
        final var event = new BridgeAuditEvent(
            "tenant-1", Instant.now(), BridgeAuditEventType.AGENT_CONNECTED,
            null, null, message);
        store.save(event);

        final var results = store.query(BridgeAuditQuery.builder().build());
        assertThat(results).hasSize(1);
        assertThat(results.get(0).message())
            .isInstanceOf(io.casehub.iot.api.bridge.BridgeMessage.Heartbeat.class);
        final var loaded = (io.casehub.iot.api.bridge.BridgeMessage.Heartbeat) results.get(0).message();
        assertThat(loaded.tenancyId()).isEqualTo("tenant-1");
    }

    @Test
    void nullMessageHandling() {
        final var event = new BridgeAuditEvent(
            "tenant-1", Instant.now(), BridgeAuditEventType.AGENT_CONNECTED,
            null, null, null);
        store.save(event);

        final var results = store.query(BridgeAuditQuery.builder().build());
        assertThat(results).hasSize(1);
        assertThat(results.get(0).message()).isNull();
    }
}
