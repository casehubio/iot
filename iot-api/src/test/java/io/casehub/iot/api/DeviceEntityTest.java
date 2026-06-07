package io.casehub.iot.api;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeviceEntityTest {

    static final class TestDevice extends DeviceEntity {
        private TestDevice(Builder builder) { super(builder); }

        static Builder builder() { return new Builder(); }

        static final class Builder extends DeviceEntity.Builder<TestDevice, Builder> {
            @Override protected Builder self() { return this; }
            @Override public TestDevice build() { return new TestDevice(this); }
        }
    }

    private TestDevice device(String id) {
        return TestDevice.builder()
            .deviceId(id)
            .deviceClass(DeviceClass.SWITCH)
            .label("Test")
            .available(true)
            .lastUpdated(Instant.parse("2026-06-07T10:00:00Z"))
            .tenancyId("tenant-1")
            .build();
    }

    @Test
    void builderSetsAllFields() {
        var d = device("d1");
        assertThat(d.deviceId()).isEqualTo("d1");
        assertThat(d.deviceClass()).isEqualTo(DeviceClass.SWITCH);
        assertThat(d.label()).isEqualTo("Test");
        assertThat(d.available()).isTrue();
        assertThat(d.lastUpdated()).isEqualTo(Instant.parse("2026-06-07T10:00:00Z"));
        assertThat(d.tenancyId()).isEqualTo("tenant-1");
    }

    @Test
    void equalsAndHashCodeOnDeviceId() {
        var a = device("d1");
        var b = device("d1");
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void differentDeviceIdNotEqual() {
        assertThat(device("d1")).isNotEqualTo(device("d2"));
    }

    @Test
    void nullDeviceIdThrows() {
        assertThatThrownBy(() -> TestDevice.builder()
            .deviceClass(DeviceClass.SWITCH).label("X")
            .available(true).lastUpdated(Instant.now()).tenancyId("t1")
            .build()
        ).isInstanceOf(NullPointerException.class);
    }

    @Test
    void toStringIncludesClassAndLabel() {
        var d = device("d1");
        assertThat(d.toString()).contains("TestDevice");
        assertThat(d.toString()).contains("d1");
        assertThat(d.toString()).contains("Test");
    }
}
