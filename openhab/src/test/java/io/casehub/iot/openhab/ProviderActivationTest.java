package io.casehub.iot.openhab;

import io.casehub.iot.api.spi.DeviceProvider;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@TestProfile(ProviderActivationTest.DisabledProfile.class)
class ProviderActivationTest {

    @Inject @Any Instance<DeviceProvider> providers;

    @Test
    void disabledProviderNotDiscoverable() {
        assertThat(providers.stream().count()).isZero();
    }

    public static class DisabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "casehub.iot.openhab.enabled", "false",
                "casehub.iot.tenancy-id", "test-tenant"
            );
        }
    }
}
