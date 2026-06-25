package io.casehub.iot.testing;

import io.casehub.iot.api.CameraDevice;
import io.casehub.iot.api.DeviceClass;
import io.casehub.iot.api.DeviceEntity;
import io.casehub.iot.api.LockDevice;
import io.casehub.iot.api.ThermostatMode;
import org.junit.jupiter.api.Test;
import java.util.stream.Collectors;
import static org.assertj.core.api.Assertions.assertThat;

class FixturesTest {

    @Test
    void allTenFactoriesProduceDevicesWithCorrectIds() {
        assertThat(Fixtures.hallwaySwitch().deviceId()).isEqualTo("switch-hallway-1");
        assertThat(Fixtures.livingRoomLight().deviceId()).isEqualTo("light-living-1");
        assertThat(Fixtures.livingRoomThermostat().deviceId()).isEqualTo("thermostat-living-1");
        assertThat(Fixtures.outdoorTemperature().deviceId()).isEqualTo("sensor-outdoor-1");
        assertThat(Fixtures.frontDoorPresence().deviceId()).isEqualTo("presence-front-1");
        assertThat(Fixtures.solarPanel().deviceId()).isEqualTo("power-solar-1");
        assertThat(Fixtures.frontDoorLock().deviceId()).isEqualTo("lock-front-1");
        assertThat(Fixtures.bedroomBlinds().deviceId()).isEqualTo("cover-bedroom-1");
        assertThat(Fixtures.livingRoomSpeaker().deviceId()).isEqualTo("media-living-1");
        assertThat(Fixtures.bedroomFan().deviceId()).isEqualTo("fan-bedroom-1");
        assertThat(Fixtures.securityCamera().deviceId()).isEqualTo("camera-security-1");
    }

    @Test
    void allDevicesUseDefaultTenantAndEpoch() {
        for (var device : Fixtures.standardHome()) {
            assertThat(device.tenancyId()).isEqualTo(Fixtures.DEFAULT_TENANT);
            assertThat(device.lastUpdated()).isEqualTo(Fixtures.EPOCH);
            assertThat(device.available()).isTrue();
        }
    }

    @Test
    void factoriesReturnFreshInstances() {
        assertThat(Fixtures.hallwaySwitch()).isNotSameAs(Fixtures.hallwaySwitch());
        assertThat(Fixtures.frontDoorLock()).isNotSameAs(Fixtures.frontDoorLock());
    }

    @Test
    void standardHomeContainsElevenDistinctDevices() {
        var home = Fixtures.standardHome();
        assertThat(home).hasSize(11);
        var ids = home.stream().map(DeviceEntity::deviceId).collect(Collectors.toSet());
        assertThat(ids).hasSize(11);
    }

    @Test
    void standardHomeCoversAllDeviceClasses() {
        var classes = Fixtures.standardHome().stream()
            .map(DeviceEntity::deviceClass)
            .collect(Collectors.toSet());
        assertThat(classes).containsExactlyInAnyOrder(DeviceClass.values());
    }

    @Test
    void initialDomainStatesAreCorrect() {
        assertThat(Fixtures.hallwaySwitch().isOn()).isFalse();
        assertThat(Fixtures.livingRoomLight().isOn()).isFalse();
        assertThat(Fixtures.livingRoomLight().brightness()).isEmpty();
        assertThat(Fixtures.livingRoomThermostat().mode()).isEqualTo(ThermostatMode.HEAT);
        assertThat(Fixtures.frontDoorPresence().isPresent()).isFalse();
        assertThat(Fixtures.frontDoorPresence().lastSeen()).isEqualTo(Fixtures.EPOCH);
        assertThat(Fixtures.solarPanel().power()).hasValue(new java.math.BigDecimal("3200"));
        assertThat(Fixtures.solarPanel().energy()).isEmpty();
        assertThat(Fixtures.frontDoorLock().isLocked()).isTrue();
        assertThat(Fixtures.bedroomBlinds().position()).isEmpty();
        assertThat(Fixtures.bedroomBlinds().isMoving()).isFalse();
        assertThat(Fixtures.livingRoomSpeaker().isPlaying()).isFalse();
        assertThat(Fixtures.bedroomFan().isOn()).isFalse();
        assertThat(Fixtures.securityCamera().isStreaming()).isFalse();
    }

    @Test
    void newBuilderPreservesDeviceIdAndModifiesField() {
        LockDevice unlocked = new LockDevice.Builder()
            .deviceId("lock-front-1").deviceClass(DeviceClass.LOCK)
            .label("Front Door Lock").available(true).lastUpdated(Fixtures.EPOCH)
            .tenancyId(Fixtures.DEFAULT_TENANT).providerId("test").locked(false).build();
        assertThat(unlocked.isLocked()).isFalse();
        assertThat(unlocked.deviceId()).isEqualTo("lock-front-1");
    }

    @Test
    void availabilityVariantViaToBuilder() {
        var offline = Fixtures.hallwaySwitch().toBuilder().available(false).build();
        assertThat(offline.available()).isFalse();
        assertThat(offline.deviceId()).isEqualTo("switch-hallway-1");
    }
}
