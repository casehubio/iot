package io.casehub.iot.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PlaybookBindingEventTest {

    @Test
    void stepStartCarriesDeviceIds() {
        var event = new PlaybookBindingEvent.StepStart(
                "exec-1", "tenant-1", "apply-nightmode", Set.of("light-1", "therm-1"));
        assertThat(event.executionId()).isEqualTo("exec-1");
        assertThat(event.tenancyId()).isEqualTo("tenant-1");
        assertThat(event.stepName()).isEqualTo("apply-nightmode");
        assertThat(event.deviceIds()).containsExactlyInAnyOrder("light-1", "therm-1");
        assertThat(event).isInstanceOf(PlaybookBindingEvent.class);
    }

    @Test
    void deviceProvisionedCarriesSingleDevice() {
        var event = new PlaybookBindingEvent.DeviceProvisioned(
                "exec-1", "tenant-1", "apply-nightmode", "light-1");
        assertThat(event.deviceId()).isEqualTo("light-1");
    }

    @Test
    void deviceFailedCarriesReason() {
        var event = new PlaybookBindingEvent.DeviceFailed(
                "exec-1", "tenant-1", "apply-nightmode", "therm-1", "timeout");
        assertThat(event.deviceId()).isEqualTo("therm-1");
        assertThat(event.reason()).isEqualTo("timeout");
    }

    @Test
    void stepCompleteCarriesCounts() {
        var event = new PlaybookBindingEvent.StepComplete(
                "exec-1", "tenant-1", "apply-nightmode", 3, 0);
        assertThat(event.provisioned()).isEqualTo(3);
        assertThat(event.failed()).isZero();
    }

    @Test
    void stepFailedCarriesDetails() {
        var event = new PlaybookBindingEvent.StepFailed(
                "exec-1", "tenant-1", "apply-nightmode", 2, 1,
                List.of("therm-1: timeout"));
        assertThat(event.failed()).isEqualTo(1);
        assertThat(event.failedDetails()).containsExactly("therm-1: timeout");
    }

    @Test
    void patternMatchOnVariants() {
        PlaybookBindingEvent event = new PlaybookBindingEvent.Clear("exec-1", "tenant-1");
        String result = switch (event) {
            case PlaybookBindingEvent.StepStart ss -> "start:" + ss.stepName();
            case PlaybookBindingEvent.DeviceProvisioned dp -> "prov:" + dp.deviceId();
            case PlaybookBindingEvent.DeviceFailed df -> "fail:" + df.deviceId();
            case PlaybookBindingEvent.StepComplete sc -> "complete:" + sc.provisioned();
            case PlaybookBindingEvent.StepFailed sf -> "failed:" + sf.failed();
            case PlaybookBindingEvent.Clear c -> "clear:" + c.executionId();
        };
        assertThat(result).isEqualTo("clear:exec-1");
    }
}
