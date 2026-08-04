package io.casehub.iot.mcp;

import io.casehub.platform.api.identity.CurrentPrincipal;
import io.quarkus.arc.Arc;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class McpIdentityContext {

    private final Instance<CurrentPrincipal> currentPrincipal;
    private final String configTenancyId;

    @Inject
    public McpIdentityContext(Instance<CurrentPrincipal> currentPrincipal,
                              @ConfigProperty(name = "casehub.iot.tenancy-id")
                              String configTenancyId) {
        this.currentPrincipal = currentPrincipal;
        this.configTenancyId = configTenancyId;
    }

    boolean isPrincipalAvailable() {
        return currentPrincipal.isResolvable()
                && Arc.container() != null
                && Arc.container().requestContext().isActive();
    }

    public String tenancyId() {
        if (isPrincipalAvailable()) {
            return currentPrincipal.get().tenancyId();
        }
        return configTenancyId;
    }

    public String actorId() {
        if (isPrincipalAvailable()) {
            return currentPrincipal.get().actorId();
        }
        return "mcp-agent";
    }
}
