package io.casehub.iot.webapp.app;

import io.casehub.platform.api.identity.CurrentPrincipal;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;
import java.util.Set;

@ApplicationScoped
public class DevCurrentPrincipalProducer {

    @Produces
    @RequestScoped
    @Alternative
    @Priority(1)
    public CurrentPrincipal produce() {
        return new CurrentPrincipal() {
            @Override
            public String actorId() {
                return "dev-user";
            }

            @Override
            public Set<String> groups() {
                return Set.of();
            }

            @Override
            public String tenancyId() {
                return "default-tenant";
            }

            @Override
            public boolean isCrossTenantAdmin() {
                return false;
            }
        };
    }
}
