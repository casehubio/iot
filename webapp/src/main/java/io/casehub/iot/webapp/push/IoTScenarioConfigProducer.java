package io.casehub.iot.webapp.push;

import io.casehub.pages.playbook.runtime.PlaybookConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Map;

@ApplicationScoped
public class IoTPlaybookConfigProducer {

    @Produces
    @ApplicationScoped
    public PlaybookConfig scenarioConfig(
            @ConfigProperty(name = "casehub.scenario.graphql-endpoint",
                            defaultValue = "http://localhost:8080/graphql") String graphqlEndpoint,
            @ConfigProperty(name = "casehub.scenario.push-endpoint",
                            defaultValue = "ws://localhost:8080/push") String pushEndpoint) {
        return new PlaybookConfig(graphqlEndpoint, pushEndpoint, Map.of(), Map.of());
    }
}
