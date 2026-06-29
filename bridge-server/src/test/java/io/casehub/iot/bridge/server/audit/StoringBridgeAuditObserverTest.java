package io.casehub.iot.bridge.server.audit;

import io.casehub.iot.api.bridge.BridgeAuditEvent;
import io.casehub.iot.api.bridge.BridgeAuditEventType;
import io.casehub.iot.api.bridge.BridgeAuditQuery;
import io.casehub.iot.api.bridge.BridgeAuditStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StoringBridgeAuditObserverTest {

    @Test
    void onAuditDelegatesToStoreSave() {
        final var saved = new ArrayList<BridgeAuditEvent>();
        final BridgeAuditStore store = new BridgeAuditStore() {
            @Override
            public void save(final BridgeAuditEvent event) { saved.add(event); }

            @Override
            public List<BridgeAuditEvent> query(final BridgeAuditQuery query) { return List.of(); }
        };
        final var observer = new StoringBridgeAuditObserver(store);
        final var event = new BridgeAuditEvent(
            "tenant-1", Instant.now(), BridgeAuditEventType.STATE_CHANGE,
            null, "light.kitchen", null);

        observer.onAudit(event);

        assertThat(saved).containsExactly(event);
    }
}
