package io.casehub.iot.openhab;

import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenHabAuthHeadersFactoryTest {

    @Test
    void bearerProducesCorrectHeader() {
        var factory = buildFactory(
                "casehub.iot.openhab.auth.bearer.token", "my-token");

        MultivaluedMap<String, String> result = factory.update(
                new MultivaluedHashMap<>(), new MultivaluedHashMap<>());

        assertThat(result.getFirst("Authorization")).isEqualTo("Bearer my-token");
    }

    @Test
    void basicProducesCorrectBase64Header() {
        var factory = buildFactory(
                "casehub.iot.openhab.auth.basic.username", "admin",
                "casehub.iot.openhab.auth.basic.password", "secret");

        MultivaluedMap<String, String> result = factory.update(
                new MultivaluedHashMap<>(), new MultivaluedHashMap<>());

        String expected = "Basic " + Base64.getEncoder().encodeToString(
                "admin:secret".getBytes(StandardCharsets.UTF_8));
        assertThat(result.getFirst("Authorization")).isEqualTo(expected);
    }

    @Test
    void anonymousProducesNoHeader() {
        var factory = buildFactory();

        MultivaluedMap<String, String> result = factory.update(
                new MultivaluedHashMap<>(), new MultivaluedHashMap<>());

        assertThat(result).isEmpty();
    }

    @Test
    void bothConfiguredThrowsAtConstruction() {
        assertThatThrownBy(() -> buildFactory(
                "casehub.iot.openhab.auth.bearer.token", "my-token",
                "casehub.iot.openhab.auth.basic.username", "admin",
                "casehub.iot.openhab.auth.basic.password", "secret"))
            .isInstanceOf(IllegalStateException.class);
    }

    private static OpenHabAuthHeadersFactory buildFactory(String... keyValues) {
        SmallRyeConfigBuilder builder = new SmallRyeConfigBuilder()
                .withMapping(OpenHabConfig.class)
                .withDefaultValue("casehub.iot.openhab.url", "http://localhost:8080")
                .withDefaultValue("casehub.iot.openhab.tenancy-id", "test");
        for (int i = 0; i < keyValues.length; i += 2) {
            builder.withDefaultValue(keyValues[i], keyValues[i + 1]);
        }
        SmallRyeConfig config = builder.build();
        OpenHabConfig ohConfig = config.getConfigMapping(OpenHabConfig.class);

        var factory = new OpenHabAuthHeadersFactory(ohConfig);
        factory.resolve();
        return factory;
    }
}
