package io.casehub.iot.bridge.server.audit;

import io.casehub.iot.api.bridge.BridgeAuditEvent;
import io.casehub.iot.api.bridge.BridgeAuditEventType;
import io.casehub.iot.api.bridge.BridgeAuditQuery;
import io.casehub.iot.api.bridge.BridgeAuditStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class AuditObserverCoexistenceTest {

    @Inject Event<BridgeAuditEvent> auditEvents;
    @Inject BridgeAuditStore store;

    private TestLogHandler logHandler;

    @BeforeEach
    void installLogHandler() {
        logHandler = new TestLogHandler();
        Logger.getLogger("io.casehub.iot.bridge.audit").addHandler(logHandler);
    }

    @AfterEach
    void removeLogHandler() {
        Logger.getLogger("io.casehub.iot.bridge.audit").removeHandler(logHandler);
    }

    @Test
    void bothObserversReceiveAsyncEvent() throws Exception {
        final var event = new BridgeAuditEvent(
            "coexistence-tenant", Instant.now(), BridgeAuditEventType.STATE_CHANGE,
            "coexistence-test", "coexistence-tenant/light-1", null);

        auditEvents.fireAsync(event).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertThat(store.query(BridgeAuditQuery.builder()
            .correlationId("coexistence-test").build()))
            .as("StoringBridgeAuditObserver should have persisted the event")
            .hasSize(1)
            .containsExactly(event);

        assertThat(logHandler.records)
            .as("LoggingBridgeAuditObserver should have logged the event")
            .hasSize(1);
    }

    private static class TestLogHandler extends Handler {
        final List<LogRecord> records = new CopyOnWriteArrayList<>();

        @Override
        public void publish(final LogRecord record) {
            records.add(record);
        }

        @Override public void flush() {}
        @Override public void close() {}
    }
}
