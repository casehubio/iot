package io.casehub.iot.bridge.server.audit;

import io.casehub.iot.api.bridge.BridgeAuditEvent;
import io.casehub.iot.api.bridge.BridgeAuditQuery;
import io.casehub.iot.api.bridge.BridgeAuditStore;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * No-op fallback implementation of {@link BridgeAuditStore}.
 * Active when no persistence module is on the classpath.
 * <p>
 * {@code @DefaultBean} gives this the lowest CDI priority — any
 * {@code @Alternative} or normal bean will displace it.
 */
@DefaultBean
@ApplicationScoped
public class NoOpBridgeAuditStore implements BridgeAuditStore {

    @Override
    public void save(final BridgeAuditEvent event) {
        // no-op — no persistence module on classpath
    }

    @Override
    public List<BridgeAuditEvent> query(final BridgeAuditQuery query) {
        return List.of();
    }
}
