package io.casehub.iot.api;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

class CapabilitiesTest {

    static final Instant NOW = Instant.parse("2026-06-07T10:00:00Z");

    // Helper — minimal device for testing DeviceEntity base behavior
    private SwitchDevice sw(boolean on) {
        return SwitchDevice.builder()
            .deviceId("sw1").deviceClass(DeviceClass.SWITCH).label("Switch")
            .available(true).lastUpdated(NOW).tenancyId("t1").on(on).build();
    }

    @Test
    void baseCapabilitiesContainsCAPAVAILABLE() {
        var device = sw(false);
        assertThat(device.capabilities()).containsKey(DeviceEntity.CAP_AVAILABLE);
        assertThat(device.capabilities().get(DeviceEntity.CAP_AVAILABLE)).isEqualTo(true);
    }

    @Test
    void capabilitiesAllocatesFreshMapEachCall() {
        var device = sw(false);
        var caps1 = device.capabilities();
        caps1.put("injected", "value");
        assertThat(device.capabilities()).doesNotContainKey("injected");
    }

    @Test
    void unavailableDeviceCapabilitiesShowsFalse() {
        var device = SwitchDevice.builder()
            .deviceId("sw1").deviceClass(DeviceClass.SWITCH).label("Switch")
            .available(false).lastUpdated(NOW).tenancyId("t1").on(false).build();
        assertThat(device.capabilities().get(DeviceEntity.CAP_AVAILABLE)).isEqualTo(false);
    }
}
