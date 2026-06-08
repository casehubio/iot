package io.casehub.iot.api;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

class ToBuilderTest {

    static final Instant NOW = Instant.parse("2026-06-07T10:00:00Z");

    @Test
    void switchDeviceToBuilderRoundTrip() {
        var original = SwitchDevice.builder()
            .deviceId("sw1").deviceClass(DeviceClass.SWITCH).label("Switch")
            .available(true).lastUpdated(NOW).tenancyId("t1").on(true).build();
        var copy = original.toBuilder().build();
        assertThat(copy.deviceId()).isEqualTo("sw1");
        assertThat(copy.deviceClass()).isEqualTo(DeviceClass.SWITCH);
        assertThat(copy.label()).isEqualTo("Switch");
        assertThat(copy.available()).isTrue();
        assertThat(copy.lastUpdated()).isEqualTo(NOW);
        assertThat(copy.tenancyId()).isEqualTo("t1");
        assertThat(copy.isOn()).isTrue();
    }

    @Test
    void switchDeviceToBuilderModifyOn() {
        var original = SwitchDevice.builder()
            .deviceId("sw1").deviceClass(DeviceClass.SWITCH).label("Switch")
            .available(true).lastUpdated(NOW).tenancyId("t1").on(true).build();
        SwitchDevice modified = original.toBuilder().on(false).build();
        assertThat(modified.isOn()).isFalse();
        assertThat(modified.deviceId()).isEqualTo("sw1");
        assertThat(modified).isInstanceOf(SwitchDevice.class);
    }
}
