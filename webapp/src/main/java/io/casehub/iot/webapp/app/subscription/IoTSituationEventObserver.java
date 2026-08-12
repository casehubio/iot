package io.casehub.iot.webapp.app.subscription;

import io.casehub.iot.api.IoTSituationEvent;
import io.casehub.platform.api.datasource.DataSource;
import io.casehub.platform.api.datasource.DataSourceRegistry;
import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.ras.api.SituationChangeEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class IoTSituationEventObserver {

    private static final Logger LOG = Logger.getLogger(IoTSituationEventObserver.class);

    private final DataSourceRegistry dataSourceRegistry;

    @Inject
    public IoTSituationEventObserver(DataSourceRegistry dataSourceRegistry) {
        this.dataSourceRegistry = dataSourceRegistry;
    }

    @SuppressWarnings("unchecked")
    public void onSituationChange(@ObservesAsync SituationChangeEvent event) {
        if (event.changeType() != SituationChangeEvent.ChangeType.TRIGGERED
                && event.changeType() != SituationChangeEvent.ChangeType.RESOLVED) {
            return;
        }

        try {
            String changeType = event.changeType() == SituationChangeEvent.ChangeType.TRIGGERED
                    ? "triggered" : "resolved";
            String deviceId = extractDeviceId(event.correlationKey());

            var situationEvent = new IoTSituationEvent(
                    event.situationId(),
                    changeType,
                    deviceId,
                    event.tenancyId(),
                    event.metadata(),
                    event.context().lastTriggered() != null
                            ? event.context().lastTriggered()
                            : event.context().lastSignal()
            );

            dataSourceRegistry
                    .resolveSource(IoTNotificationDataSourceRegistrar.IOT_SITUATIONS_PATH,
                            TenancyConstants.PLATFORM_TENANT_ID)
                    .ifPresentOrElse(
                            ds -> ((DataSource<IoTSituationEvent>) ds).add(situationEvent),
                            () -> LOG.warn("IoT situations DataSource not registered — event dropped: "
                                    + situationEvent.type())
                    );
        } catch (Exception e) {
            LOG.warnf(e, "Failed to push IoT situation event [%s/%s]: %s",
                    event.situationId(), event.correlationKey(), e.getMessage());
        }
    }

    private static String extractDeviceId(String correlationKey) {
        if (correlationKey != null && correlationKey.startsWith("device/")) {
            return correlationKey.substring("device/".length());
        }
        return correlationKey != null ? correlationKey : "unknown";
    }
}
