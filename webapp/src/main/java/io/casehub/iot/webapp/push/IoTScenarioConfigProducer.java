package io.casehub.iot.webapp.push;

import io.casehub.pages.scenario.runtime.ScenarioConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Map;

@ApplicationScoped
public class IoTScenarioConfigProducer {

    @Produces
    @ApplicationScoped
    public ScenarioConfig scenarioConfig(
            @ConfigProperty(name = "casehub.scenario.graphql-endpoint",
                            defaultValue = "http://localhost:8080/graphql") String graphqlEndpoint,
            @ConfigProperty(name = "casehub.scenario.push-endpoint",
                            defaultValue = "ws://localhost:8080/push") String pushEndpoint) {
        return new ScenarioConfig(graphqlEndpoint, pushEndpoint, Map.of(), Map.of());
    }
}
