package io.casehub.iot.openhab;

import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenHabConfigPartialTest {

    @Test
    void partialBasicConfigFailsAtBuildTime() {
        assertThatThrownBy(() -> new SmallRyeConfigBuilder()
                .withMapping(OpenHabConfig.class)
                .withDefaultValue("casehub.iot.openhab.url", "http://localhost:8080")
                .withDefaultValue("casehub.iot.openhab.tenancy-id", "test")
                .withDefaultValue("casehub.iot.openhab.auth.basic.username", "admin")
                .build()
                .getConfigMapping(OpenHabConfig.class))
            .hasMessageContaining("password");
    }
}
