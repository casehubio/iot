package io.casehub.iot.webapp.app.subscription;

import io.casehub.iot.api.IoTSituationEvent;
import io.casehub.platform.api.datasource.DataSource;
import io.casehub.platform.api.datasource.DataSourceRegistry;
import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.platform.api.path.Path;
import io.casehub.ras.api.SituationChangeEvent;
import io.casehub.ras.api.SituationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class IoTSituationEventObserverTest {

    private static final Instant TRIGGERED_AT = Instant.parse("2026-08-12T10:00:00Z");

    private DataSourceRegistry registry;
    private DataSource<IoTSituationEvent> dataSource;
    private IoTSituationEventObserver observer;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        registry = mock(DataSourceRegistry.class);
        dataSource = mock(DataSource.class);
        when(registry.resolveSource(any(Path.class), eq(TenancyConstants.PLATFORM_TENANT_ID)))
                .thenReturn(Optional.of(dataSource));
        observer = new IoTSituationEventObserver(registry);
    }

    private SituationContext context() {
        return SituationContext.initial("temperature-threshold", "device/sensor.outdoor",
                "tenant-1", TRIGGERED_AT);
    }

    @Test
    void triggeredEventPushedToDataSource() {
        var event = new SituationChangeEvent(
                "tenant-1", "temperature-threshold", "device/sensor.outdoor",
                SituationChangeEvent.ChangeType.TRIGGERED, context(),
                Map.of("temperature", 42.0));

        observer.onSituationChange(event);

        var captor = ArgumentCaptor.forClass(IoTSituationEvent.class);
        verify(dataSource).add(captor.capture());
        var captured = captor.getValue();
        assertThat(captured.type()).isEqualTo("io.casehub.iot.situation.triggered.temperature-threshold");
        assertThat(captured.deviceId()).isEqualTo("sensor.outdoor");
        assertThat(captured.tenancyId()).isEqualTo("tenant-1");
        assertThat(captured.metadata()).containsEntry("temperature", 42.0);
    }

    @Test
    void resolvedEventPushedToDataSource() {
        var event = new SituationChangeEvent(
                "tenant-1", "temperature-threshold", "device/sensor.outdoor",
                SituationChangeEvent.ChangeType.RESOLVED, context());

        observer.onSituationChange(event);

        var captor = ArgumentCaptor.forClass(IoTSituationEvent.class);
        verify(dataSource).add(captor.capture());
        assertThat(captor.getValue().type()).isEqualTo("io.casehub.iot.situation.resolved.temperature-threshold");
    }

    @Test
    void suppressedEventIsIgnored() {
        var event = new SituationChangeEvent(
                "tenant-1", "temperature-threshold", "device/sensor.outdoor",
                SituationChangeEvent.ChangeType.SUPPRESSED, context());

        observer.onSituationChange(event);

        verify(dataSource, never()).add(any());
    }

    @Test
    void dismissedEventIsIgnored() {
        var event = new SituationChangeEvent(
                "tenant-1", "temperature-threshold", "device/sensor.outdoor",
                SituationChangeEvent.ChangeType.DISMISSED, context());

        observer.onSituationChange(event);

        verify(dataSource, never()).add(any());
    }

    @Test
    void discardedEventIsIgnored() {
        var event = new SituationChangeEvent(
                "tenant-1", "temperature-threshold", "device/sensor.outdoor",
                SituationChangeEvent.ChangeType.DISCARDED, context());

        observer.onSituationChange(event);

        verify(dataSource, never()).add(any());
    }

    @Test
    void dataSourceFailureIsCaughtAndLogged() {
        doThrow(new RuntimeException("DataSource unavailable")).when(dataSource).add(any());
        var event = new SituationChangeEvent(
                "tenant-1", "temperature-threshold", "device/sensor.outdoor",
                SituationChangeEvent.ChangeType.TRIGGERED, context(),
                Map.of());

        observer.onSituationChange(event);

        verify(dataSource).add(any());
    }

    @Test
    void correlationKeyWithoutDevicePrefixUsedAsIs() {
        var ctx = SituationContext.initial("power-anomaly", "sensor.power_meter",
                "tenant-1", TRIGGERED_AT);
        var event = new SituationChangeEvent(
                "tenant-1", "power-anomaly", "sensor.power_meter",
                SituationChangeEvent.ChangeType.TRIGGERED, ctx,
                Map.of());

        observer.onSituationChange(event);

        var captor = ArgumentCaptor.forClass(IoTSituationEvent.class);
        verify(dataSource).add(captor.capture());
        assertThat(captor.getValue().deviceId()).isEqualTo("sensor.power_meter");
    }
}
