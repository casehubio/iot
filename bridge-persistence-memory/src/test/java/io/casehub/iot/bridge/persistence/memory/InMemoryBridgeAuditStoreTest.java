package io.casehub.iot.bridge.persistence.memory;

import io.casehub.iot.api.bridge.BridgeAuditEvent;
import io.casehub.iot.api.bridge.BridgeAuditEventType;
import io.casehub.iot.api.bridge.BridgeAuditQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryBridgeAuditStoreTest {

    private InMemoryBridgeAuditStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryBridgeAuditStore(100);
    }

    @Test
    void savedEventsAreRetrievableByQuery() {
        final var event = auditEvent("tenant-1", BridgeAuditEventType.STATE_CHANGE, "light.kitchen", null);
        store.save(event);

        final var results = store.query(BridgeAuditQuery.builder().build());
        assertThat(results).containsExactly(event);
    }

    @Test
    void boundedEvictionDropsOldestEvents() {
        final var smallStore = new InMemoryBridgeAuditStore(3);
        final var e1 = auditEvent("t", BridgeAuditEventType.STATE_CHANGE, "d1", null);
        final var e2 = auditEvent("t", BridgeAuditEventType.STATE_CHANGE, "d2", null);
        final var e3 = auditEvent("t", BridgeAuditEventType.STATE_CHANGE, "d3", null);
        final var e4 = auditEvent("t", BridgeAuditEventType.STATE_CHANGE, "d4", null);

        smallStore.save(e1);
        smallStore.save(e2);
        smallStore.save(e3);
        smallStore.save(e4);

        final var results = smallStore.query(BridgeAuditQuery.builder().build());
        assertThat(results).containsExactly(e4, e3, e2);
        assertThat(results).doesNotContain(e1);
    }

    @Test
    void queryFiltersByTenancyId() {
        store.save(auditEvent("tenant-1", BridgeAuditEventType.STATE_CHANGE, "d1", null));
        store.save(auditEvent("tenant-2", BridgeAuditEventType.STATE_CHANGE, "d2", null));

        final var results = store.query(BridgeAuditQuery.builder().tenancyId("tenant-1").build());
        assertThat(results).hasSize(1);
        assertThat(results.get(0).tenancyId()).isEqualTo("tenant-1");
    }

    @Test
    void queryFiltersByEventType() {
        store.save(auditEvent("t", BridgeAuditEventType.STATE_CHANGE, "d1", null));
        store.save(auditEvent("t", BridgeAuditEventType.COMMAND_SENT, "d2", "corr-1"));

        final var results = store.query(BridgeAuditQuery.builder().eventType(BridgeAuditEventType.COMMAND_SENT).build());
        assertThat(results).hasSize(1);
        assertThat(results.get(0).eventType()).isEqualTo(BridgeAuditEventType.COMMAND_SENT);
    }

    @Test
    void queryFiltersByDeviceId() {
        store.save(auditEvent("t", BridgeAuditEventType.STATE_CHANGE, "light.kitchen", null));
        store.save(auditEvent("t", BridgeAuditEventType.STATE_CHANGE, "switch.hall", null));

        final var results = store.query(BridgeAuditQuery.builder().deviceId("light.kitchen").build());
        assertThat(results).hasSize(1);
        assertThat(results.get(0).deviceId()).isEqualTo("light.kitchen");
    }

    @Test
    void queryFiltersByCorrelationId() {
        store.save(auditEvent("t", BridgeAuditEventType.COMMAND_SENT, "d1", "corr-A"));
        store.save(auditEvent("t", BridgeAuditEventType.COMMAND_RESPONSE, "d1", "corr-A"));
        store.save(auditEvent("t", BridgeAuditEventType.COMMAND_SENT, "d2", "corr-B"));

        final var results = store.query(BridgeAuditQuery.builder().correlationId("corr-A").build());
        assertThat(results).hasSize(2);
        assertThat(results).allSatisfy(e -> assertThat(e.correlationId()).isEqualTo("corr-A"));
    }

    @Test
    void queryFiltersByTimeRange() {
        final Instant now = Instant.now();
        final Instant oneHourAgo = now.minus(1, ChronoUnit.HOURS);
        final Instant twoHoursAgo = now.minus(2, ChronoUnit.HOURS);

        store.save(auditEventAt("t", BridgeAuditEventType.STATE_CHANGE, "d1", twoHoursAgo));
        store.save(auditEventAt("t", BridgeAuditEventType.STATE_CHANGE, "d2", oneHourAgo));
        store.save(auditEventAt("t", BridgeAuditEventType.STATE_CHANGE, "d3", now));

        final var results = store.query(BridgeAuditQuery.builder()
            .from(oneHourAgo.minus(1, ChronoUnit.MINUTES))
            .to(now.plus(1, ChronoUnit.MINUTES))
            .build());
        assertThat(results).hasSize(2);
    }

    @Test
    void queryComposesMultipleCriteria() {
        store.save(auditEvent("tenant-1", BridgeAuditEventType.COMMAND_SENT, "light.kitchen", "corr-1"));
        store.save(auditEvent("tenant-1", BridgeAuditEventType.STATE_CHANGE, "light.kitchen", null));
        store.save(auditEvent("tenant-2", BridgeAuditEventType.COMMAND_SENT, "light.kitchen", "corr-2"));

        final var results = store.query(BridgeAuditQuery.builder()
            .tenancyId("tenant-1")
            .eventType(BridgeAuditEventType.COMMAND_SENT)
            .build());
        assertThat(results).hasSize(1);
        assertThat(results.get(0).correlationId()).isEqualTo("corr-1");
    }

    @Test
    void queryLimitsResults() {
        for (int i = 0; i < 10; i++) {
            store.save(auditEvent("t", BridgeAuditEventType.STATE_CHANGE, "d" + i, null));
        }

        final var results = store.query(BridgeAuditQuery.builder().limit(3).build());
        assertThat(results).hasSize(3);
    }

    @Test
    void queryReturnsNewestFirst() {
        final Instant t1 = Instant.now().minus(2, ChronoUnit.HOURS);
        final Instant t2 = Instant.now().minus(1, ChronoUnit.HOURS);
        final Instant t3 = Instant.now();

        store.save(auditEventAt("t", BridgeAuditEventType.STATE_CHANGE, "d1", t1));
        store.save(auditEventAt("t", BridgeAuditEventType.STATE_CHANGE, "d2", t2));
        store.save(auditEventAt("t", BridgeAuditEventType.STATE_CHANGE, "d3", t3));

        final var results = store.query(BridgeAuditQuery.builder().build());
        assertThat(results).hasSize(3);
        assertThat(results.get(0).receivedAt()).isEqualTo(t3);
        assertThat(results.get(1).receivedAt()).isEqualTo(t2);
        assertThat(results.get(2).receivedAt()).isEqualTo(t1);
    }

    @Test
    void emptyStoreReturnsEmptyList() {
        final var results = store.query(BridgeAuditQuery.builder().build());
        assertThat(results).isEmpty();
    }

    @Test
    void nullCriteriaReturnsAllEventsUpToLimit() {
        for (int i = 0; i < 5; i++) {
            store.save(auditEvent("t", BridgeAuditEventType.STATE_CHANGE, "d" + i, null));
        }

        final var results = store.query(BridgeAuditQuery.builder().build());
        assertThat(results).hasSize(5);
    }

    @Test
    void contradictoryTimeRangeProducesEmptyResults() {
        final Instant now = Instant.now();
        store.save(auditEventAt("t", BridgeAuditEventType.STATE_CHANGE, "d1", now));

        final var results = store.query(BridgeAuditQuery.builder()
            .from(now.plus(1, ChronoUnit.HOURS))
            .to(now.minus(1, ChronoUnit.HOURS))
            .build());
        assertThat(results).isEmpty();
    }

    @Test
    void concurrentSaveAndQueryDoesNotThrow() throws Exception {
        final int threads = 4;
        final int eventsPerThread = 250;
        final ExecutorService executor = Executors.newFixedThreadPool(threads);
        final CountDownLatch latch = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < eventsPerThread; i++) {
                        store.save(auditEvent("t" + threadId, BridgeAuditEventType.STATE_CHANGE, "d" + i, null));
                        store.query(BridgeAuditQuery.builder().tenancyId("t" + threadId).build());
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        final var all = store.query(BridgeAuditQuery.builder().limit(100).build());
        assertThat(all).isNotEmpty();
    }

    @Test
    void queryRespectsOffset() {
        for (int i = 0; i < 10; i++) {
            store.save(auditEvent("t", BridgeAuditEventType.STATE_CHANGE, "d" + i, null));
        }

        final var results = store.query(BridgeAuditQuery.builder().offset(3).limit(3).build());
        assertThat(results).hasSize(3);
        // newest-first: d9, d8, d7, d6, d5, d4, d3, d2, d1, d0
        // offset 3 skips d9, d8, d7 → returns d6, d5, d4
        assertThat(results).extracting(BridgeAuditEvent::deviceId)
            .containsExactly("d6", "d5", "d4");
    }

    private static BridgeAuditEvent auditEvent(final String tenancyId, final BridgeAuditEventType type,
                                                final String deviceId, final String correlationId) {
        return new BridgeAuditEvent(tenancyId, Instant.now(), type, correlationId, deviceId, null);
    }

    private static BridgeAuditEvent auditEventAt(final String tenancyId, final BridgeAuditEventType type,
                                                  final String deviceId, final Instant receivedAt) {
        return new BridgeAuditEvent(tenancyId, receivedAt, type, null, deviceId, null);
    }
}
