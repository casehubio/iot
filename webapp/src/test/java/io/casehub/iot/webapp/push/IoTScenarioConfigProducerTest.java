package io.casehub.iot.webapp.push;

import io.casehub.pages.scenario.runtime.ScenarioConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IoTScenarioConfigProducerTest {

    @Test
    void producesConfigWithDefaults() {
        var producer = new IoTScenarioConfigProducer();
        ScenarioConfig config = producer.scenarioConfig(
                "http://localhost:8080/graphql",
                "ws://localhost:8080/push");

        assertThat(config.graphQLEndpoint("any-domain"))
                .isEqualTo("http://localhost:8080/graphql");
        assertThat(config.pushEndpoint("any-domain"))
                .isEqualTo("ws://localhost:8080/push");
    }
}
