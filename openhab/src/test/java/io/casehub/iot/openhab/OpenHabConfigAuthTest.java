package io.casehub.iot.openhab;

import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenHabConfigAuthTest {

    @Test
    void bearerPresentWhenTokenConfigured() {
        OpenHabConfig config = buildConfig(
                "casehub.iot.openhab.url", "http://localhost:8080",
                "casehub.iot.openhab.tenancy-id", "test",
                "casehub.iot.openhab.auth.bearer.token", "my-token");

        assertThat(config.auth().bearer()).isPresent();
        assertThat(config.auth().bearer().get().token()).isEqualTo("my-token");
        assertThat(config.auth().basic()).isEmpty();
    }

    @Test
    void basicPresentWhenUsernameAndPasswordConfigured() {
        OpenHabConfig config = buildConfig(
                "casehub.iot.openhab.url", "http://localhost:8080",
                "casehub.iot.openhab.tenancy-id", "test",
                "casehub.iot.openhab.auth.basic.username", "admin",
                "casehub.iot.openhab.auth.basic.password", "secret");

        assertThat(config.auth().basic()).isPresent();
        assertThat(config.auth().basic().get().username()).isEqualTo("admin");
        assertThat(config.auth().basic().get().password()).isEqualTo("secret");
        assertThat(config.auth().bearer()).isEmpty();
    }

    @Test
    void anonymousWhenNoAuthConfigured() {
        OpenHabConfig config = buildConfig(
                "casehub.iot.openhab.url", "http://localhost:8080",
                "casehub.iot.openhab.tenancy-id", "test");

        assertThat(config.auth().bearer()).isEmpty();
        assertThat(config.auth().basic()).isEmpty();
    }

    @Test
    void bothPresentWhenBothConfigured() {
        OpenHabConfig config = buildConfig(
                "casehub.iot.openhab.url", "http://localhost:8080",
                "casehub.iot.openhab.tenancy-id", "test",
                "casehub.iot.openhab.auth.bearer.token", "my-token",
                "casehub.iot.openhab.auth.basic.username", "admin",
                "casehub.iot.openhab.auth.basic.password", "secret");

        assertThat(config.auth().bearer()).isPresent();
        assertThat(config.auth().basic()).isPresent();
    }

    private static OpenHabConfig buildConfig(String... keyValues) {
        SmallRyeConfigBuilder builder = new SmallRyeConfigBuilder()
                .withMapping(OpenHabConfig.class);
        for (int i = 0; i < keyValues.length; i += 2) {
            builder.withDefaultValue(keyValues[i], keyValues[i + 1]);
        }
        SmallRyeConfig config = builder.build();
        return config.getConfigMapping(OpenHabConfig.class);
    }
}
