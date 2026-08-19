package io.casehub.iot.webapp.app.rest;

import io.casehub.iot.api.DeviceClass;
import io.casehub.iot.api.DeviceEntity;
import io.casehub.iot.api.LightDevice;
import io.casehub.iot.api.SwitchDevice;
import io.casehub.iot.api.spi.DeviceRegistry;
import io.casehub.iot.webapp.rest.KpiMetric;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class KpiResourceTest {

    private KpiResource resource;
    private TestDeviceRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new TestDeviceRegistry();
        resource = new KpiResource();
        resource.deviceRegistry = registry;
    }

    @Test
    void deviceKpiReturnsFourMetrics() {
        var now = java.time.Instant.now();
        registry.devices = List.of(
            SwitchDevice.builder().deviceId("sw1").tenancyId("t1")
                .deviceClass(DeviceClass.SWITCH).lastUpdated(now)
                .providerId("openhab").label("Switch").available(true)
                .on(true).build(),
            new LightDevice.Builder().deviceId("lt1").tenancyId("t1")
                .deviceClass(DeviceClass.LIGHT).lastUpdated(now)
                .providerId("ha").label("Light").available(false)
                .on(false).build(),
            new LightDevice.Builder().deviceId("lt2").tenancyId("t1")
                .deviceClass(DeviceClass.LIGHT).lastUpdated(now)
                .providerId("ha").label("Light2").available(false)
                .on(false).build()
        );

        List<KpiMetric> metrics = resource.deviceKpi("t1");

        assertThat(metrics).hasSize(4);
        assertThat(metrics).extracting(KpiMetric::key)
            .containsExactlyInAnyOrder("total-devices", "online", "providers", "active-alerts");

        var total = metrics.stream().filter(m -> "total-devices".equals(m.key())).findFirst().orElseThrow();
        assertThat(total.value()).isEqualTo(3L);

        var online = metrics.stream().filter(m -> "online".equals(m.key())).findFirst().orElseThrow();
        assertThat(online.value()).isEqualTo(1L);
        assertThat(online.status()).isEqualTo("warning");

        var providers = metrics.stream().filter(m -> "providers".equals(m.key())).findFirst().orElseThrow();
        assertThat(providers.value()).isEqualTo(2L);
}

    @Test
    void deviceKpiOnlineStatusNormalAbove50Percent() {
        var now = java.time.Instant.now();
        registry.devices = List.of(
            SwitchDevice.builder().deviceId("sw1").tenancyId("t1")
                .deviceClass(DeviceClass.SWITCH).lastUpdated(now)
                .providerId("ha").label("S1").available(true).on(true).build(),
            SwitchDevice.builder().deviceId("sw2").tenancyId("t1")
                .deviceClass(DeviceClass.SWITCH).lastUpdated(now)
                .providerId("ha").label("S2").available(true).on(true).build()
        );

        List<KpiMetric> metrics = resource.deviceKpi("t1");

        var online = metrics.stream().filter(m -> "online".equals(m.key())).findFirst().orElseThrow();
        assertThat(online.status()).isEqualTo("normal");
}

    @Test
    void deviceKpiEmptyRegistryReturnsZeroCounts() {
        registry.devices = List.of();

        List<KpiMetric> metrics = resource.deviceKpi("t1");

        assertThat(metrics).hasSize(4);
        var total = metrics.stream().filter(m -> "total-devices".equals(m.key())).findFirst().orElseThrow();
        assertThat(total.value()).isEqualTo(0L);
        assertThat(total.status()).isEqualTo("normal");
    }

    @Test
    void deviceKpiFiltersByTenancy() {
        var now = java.time.Instant.now();
        registry.devices = List.of(
            SwitchDevice.builder().deviceId("sw1").tenancyId("t1")
                .deviceClass(DeviceClass.SWITCH).lastUpdated(now)
                .providerId("ha").label("S1").available(true).on(true).build(),
            SwitchDevice.builder().deviceId("sw2").tenancyId("t2")
                .deviceClass(DeviceClass.SWITCH).lastUpdated(now)
                .providerId("ha").label("S2").available(true).on(true).build()
        );

        List<KpiMetric> metrics = resource.deviceKpi("t1");

        var total = metrics.stream().filter(m -> "total-devices".equals(m.key())).findFirst().orElseThrow();
        assertThat(total.value()).isEqualTo(1L);
}

    @Test
    void healthKpiReturnsFourMetrics() {
        List<KpiMetric> metrics = resource.healthKpi(1L, 2L);

        assertThat(metrics).hasSize(4);
        assertThat(metrics).extracting(KpiMetric::key)
            .containsExactlyInAnyOrder("connected-providers", "bridge-connections", "active-situations", "open-cases");

        var connected = metrics.stream().filter(m -> "connected-providers".equals(m.key())).findFirst().orElseThrow();
        assertThat(connected.value()).isEqualTo(1L);

        var bridges = metrics.stream().filter(m -> "bridge-connections".equals(m.key())).findFirst().orElseThrow();
        assertThat(bridges.value()).isEqualTo(2L);
    }

    @Test
    void healthKpiZeroConnectionsReturnsNormalStatus() {
        List<KpiMetric> metrics = resource.healthKpi(0L, 0L);

        assertThat(metrics).hasSize(4);
        metrics.forEach(m -> assertThat(m.status()).isEqualTo("normal"));
    }

    static class TestDeviceRegistry implements DeviceRegistry {
        List<DeviceEntity> devices = List.of();

        @Override public Optional<DeviceEntity> findById(String id) { return Optional.empty(); }
        @Override public <T extends DeviceEntity> List<T> findByClass(Class<T> c) { return List.of(); }
        @Override public List<DeviceEntity> findByTenancyId(String t) {
            return devices.stream().filter(d -> t.equals(d.tenancyId())).toList();
        }
        @Override public List<DeviceEntity> findAll() { return devices; }
        @Override public void refresh() {}
        @Override public void refresh(String p) {}
    }
}
