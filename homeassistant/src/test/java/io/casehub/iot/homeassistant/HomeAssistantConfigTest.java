package io.casehub.iot.homeassistant;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class HomeAssistantConfigTest {

    @Inject
    HomeAssistantConfig config;

    @Test
    void requiredFieldsPopulated() {
        // URL is set dynamically by TestHttpServerResource
        assertThat(config.url()).isPresent();
        assertThat(config.url().orElseThrow()).startsWith("http://localhost:");
        assertThat(config.token()).hasValue("test-token");
    }

    @Test
    void defaultsApplied() {
        assertThat(config.enabled()).isTrue(); // explicitly set in application.properties
        assertThat(config.reconnectBaseSeconds()).isEqualTo(5);
        assertThat(config.reconnectMaxSeconds()).isEqualTo(300);
        assertThat(config.pingIntervalSeconds()).isEqualTo(30);
        assertThat(config.pongTimeoutSeconds()).isEqualTo(10);
        assertThat(config.discoveryTimeoutSeconds()).isEqualTo(5);
    }
}
