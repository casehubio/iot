package io.casehub.iot.homeassistant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HomeAssistantDiscoveryTest {

    @Test
    void constructsUrlFromHostAndPort() {
        String url = HomeAssistantDiscovery.buildUrl("192.168.1.50", 8123);
        assertThat(url).isEqualTo("http://192.168.1.50:8123");
    }

    @Test
    void throwsOnTimeoutWithClearMessage() {
        assertThatThrownBy(() ->
            HomeAssistantDiscovery.resolve(0))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("_home-assistant._tcp");
    }
}
