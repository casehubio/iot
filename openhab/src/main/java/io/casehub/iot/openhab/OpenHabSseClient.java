package io.casehub.iot.openhab;

import io.casehub.iot.api.DeviceCommand;
import io.casehub.iot.api.ProviderStatus;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * SSE client stub for OpenHAB event stream.
 *
 * <p>Provides enough surface for {@link OpenHabProvider} to compile and test.
 * Full implementation (event stream, state cache, coalescing, lifecycle)
 * is deferred to Task 7.
 */
@ApplicationScoped
public class OpenHabSseClient {

    private volatile ProviderStatus currentStatus = ProviderStatus.DISCONNECTED;

    public ProviderStatus currentStatus() {
        return currentStatus;
    }

    public Uni<Void> connect() {
        return Uni.createFrom().voidItem();
    }

    /**
     * Resolves the target item name for command dispatch.
     *
     * <p>Uses the Equipment member index built during discovery.
     * Full implementation in Task 7.
     *
     * @param command the device command to resolve
     * @return the OpenHAB item name to send the command to, or null if unresolved
     */
    public String resolveTargetItem(DeviceCommand command) {
        return null;
    }
}
