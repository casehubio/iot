package io.casehub.iot.openhab;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenHabDiscoveryTest {

    @Test
    void buildUrlFromHostAndPort() {
        assertThat(OpenHabDiscovery.buildUrl("192.168.1.101", 8080, false))
            .isEqualTo("http://192.168.1.101:8080");
    }

    @Test
    void buildSslUrl() {
        assertThat(OpenHabDiscovery.buildUrl("192.168.1.101", 8443, true))
            .isEqualTo("https://192.168.1.101:8443");
    }

    @Test
    void throwsOnTimeoutWithClearMessage() {
        assertThatThrownBy(() -> OpenHabDiscovery.resolve(0))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("mDNS")
            .hasMessageContaining("SSDP");
    }

    @Test
    void parseSsdpLocationHeader() {
        String response = "HTTP/1.1 200 OK\r\nLOCATION: http://192.168.1.101:8080/rest\r\n\r\n";
        assertThat(OpenHabDiscovery.parseSsdpLocation(response))
            .isEqualTo("http://192.168.1.101:8080");
    }
}
