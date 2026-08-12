package io.casehub.iot.webapp.app.subscription;

import io.casehub.iot.webapp.app.WebappPostgresTestResource;
import io.casehub.platform.api.datasource.DataSourceRegistry;
import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.platform.api.path.Path;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@QuarkusTestResource(WebappPostgresTestResource.class)
class IoTNotificationIntegrationTest {

    @Inject
    DataSourceRegistry dataSourceRegistry;

    @Inject
    IoTSituationEventObserver observer;

    @Test
    void dataSourceRegisteredAtStartup() {
        var ds = dataSourceRegistry.resolveSource(
                Path.of("iot", "situations"),
                TenancyConstants.PLATFORM_TENANT_ID);
        assertThat(ds).isPresent();
    }

    @Test
    void observerIsInjectable() {
        assertThat(observer).isNotNull();
    }
}
