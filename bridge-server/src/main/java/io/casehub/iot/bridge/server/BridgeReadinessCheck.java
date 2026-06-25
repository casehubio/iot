package io.casehub.iot.bridge.server;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

@Readiness
@ApplicationScoped
public class BridgeReadinessCheck implements HealthCheck {

    private final BridgeConnectionRegistry registry;

    @Inject
    public BridgeReadinessCheck(BridgeConnectionRegistry registry) {
        this.registry = registry;
    }

    @Override
    public HealthCheckResponse call() {
        boolean up = registry.hasAnyConnection();

        return HealthCheckResponse.named("bridge-agents")
                .status(up)
                .withData("connectedAgents", (long) registry.connectedTenancies().size())
                .withData("knownAgents", (long) registry.knownTenancies().size())
                .build();
    }
}
