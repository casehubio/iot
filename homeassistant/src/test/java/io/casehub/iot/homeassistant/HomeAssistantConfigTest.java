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
        assertThat(config.url()).isEqualTo("http://localhost:8081");
        assertThat(config.token()).isEqualTo("test-token");
        assertThat(config.tenancyId()).isEqualTo("test-tenant");
    }

    @Test
    void defaultsApplied() {
        assertThat(config.reconnectBaseSeconds()).isEqualTo(5);
        assertThat(config.reconnectMaxSeconds()).isEqualTo(300);
        assertThat(config.pingIntervalSeconds()).isEqualTo(30);
        assertThat(config.pongTimeoutSeconds()).isEqualTo(10);
    }
}
