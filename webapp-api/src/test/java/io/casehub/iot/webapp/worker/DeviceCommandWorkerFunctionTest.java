package io.casehub.iot.webapp.worker;

import io.casehub.iot.api.CommandResult;
import io.casehub.iot.api.DeviceClass;
import io.casehub.iot.api.DeviceCommand;
import io.casehub.iot.api.LightDevice;
import io.casehub.iot.api.spi.DeviceProvider;
import io.casehub.iot.api.spi.DeviceRegistry;
import io.casehub.worker.api.WorkerOutcome;
import io.casehub.worker.api.WorkerResult;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeviceCommandWorkerFunctionTest {

    private DeviceRegistry deviceRegistry;
    private DeviceProvider deviceProvider;
    private Instance<DeviceProvider> providers;
    private DeviceCommandWorkerFunction function;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        deviceRegistry = mock(DeviceRegistry.class);
        deviceProvider = mock(DeviceProvider.class);
        providers = mock(Instance.class);

        when(providers.stream()).thenReturn(java.util.stream.Stream.of(deviceProvider));
        when(deviceProvider.providerId()).thenReturn("test-provider");

        function = new DeviceCommandWorkerFunction(providers, deviceRegistry);
    }

    @Test
    void dispatchesCommandToDevice() {
        var device = new LightDevice.Builder()
                .deviceId("light-1")
                .deviceClass(DeviceClass.LIGHT)
                .label("Living Room Light")
                .providerId("test-provider")
                .tenancyId("default-tenant")
                .available(true)
                .lastUpdated(Instant.now())
                .on(false)
                .build();

        when(deviceRegistry.findById("light-1")).thenReturn(Optional.of(device));
        when(deviceProvider.dispatch(any(DeviceCommand.class)))
                .thenReturn(Uni.createFrom().item(CommandResult.SENT));

        var input = Map.<String, Object>of(
                "targetDeviceId", "light-1",
                "action", "TURN_ON",
                "parameters", Map.of()
        );

        WorkerResult result = function.apply(input);

        assertThat(result.output()).containsEntry("result", "SENT");
    }

    @Test
    void returnsFailureWhenDeviceNotFound() {
        when(deviceRegistry.findById("unknown-device")).thenReturn(Optional.empty());

        var input = Map.<String, Object>of(
                "targetDeviceId", "unknown-device",
                "action", "TURN_ON",
                "parameters", Map.of()
        );

        WorkerResult result = function.apply(input);

        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Failed.class);
        assertThat(result.output()).containsEntry("targetDeviceId", "unknown-device");
    }

    @Test
    void returnsFailureWhenProviderNotFound() {
        var device = new LightDevice.Builder()
                .deviceId("light-1")
                .deviceClass(DeviceClass.LIGHT)
                .label("Light")
                .providerId("unknown-provider")
                .tenancyId("default-tenant")
                .available(true)
                .lastUpdated(Instant.now())
                .on(false)
                .build();

        when(deviceRegistry.findById("light-1")).thenReturn(Optional.of(device));
        when(providers.stream()).thenReturn(java.util.stream.Stream.empty());

        var input = Map.<String, Object>of(
                "targetDeviceId", "light-1",
                "action", "TURN_ON",
                "parameters", Map.of()
        );

        WorkerResult result = function.apply(input);

        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Failed.class);
        assertThat(result.output()).containsEntry("providerId", "unknown-provider");
    }
}
