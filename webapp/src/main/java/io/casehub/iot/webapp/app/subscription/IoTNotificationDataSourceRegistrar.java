package io.casehub.iot.webapp.app.subscription;

import io.casehub.iot.api.IoTSituationEvent;
import io.casehub.platform.api.datasource.DataSourceDescriptor;
import io.casehub.platform.api.datasource.DataSourceRegistry;
import io.casehub.platform.api.datasource.ObjectType;
import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.platform.api.path.Path;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class IoTNotificationDataSourceRegistrar {

    private static final Logger LOG = Logger.getLogger(IoTNotificationDataSourceRegistrar.class);

    static final Path IOT_SITUATIONS_PATH = Path.of("iot", "situations");

    private static final ObjectType<IoTSituationEvent> OBJECT_TYPE = new ObjectType<>() {
        @Override
        public boolean matches(Object obj) {
            return obj instanceof IoTSituationEvent;
        }

        @Override
        public Object getTypeKey() {
            return "io.casehub.iot.situation";
        }
    };

    @Inject
    DataSourceRegistry dataSourceRegistry;

    void onStartup(@Observes StartupEvent event) {
        var descriptor = new DataSourceDescriptor(
                IOT_SITUATIONS_PATH,
                TenancyConstants.PLATFORM_TENANT_ID,
                OBJECT_TYPE,
                IOT_SITUATIONS_PATH,
                Set.of("io.casehub.iot.situation.triggered", "io.casehub.iot.situation.resolved"),
                Map.of(),
                Map.of()
        );
        dataSourceRegistry.register(descriptor);
        LOG.info("Registered IoT situations DataSource at " + IOT_SITUATIONS_PATH);
    }
}
