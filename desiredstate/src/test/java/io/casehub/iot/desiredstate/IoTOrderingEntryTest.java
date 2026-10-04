package io.casehub.iot.desiredstate;

import io.casehub.iot.api.DeviceClass;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IoTOrderingEntryTest {

    @Test
    void validEntry() {
        var entry = new IoTOrderingEntry(DeviceClass.LOCK, DeviceClass.LIGHT);
        assertThat(entry.before()).isEqualTo(DeviceClass.LOCK);
        assertThat(entry.after()).isEqualTo(DeviceClass.LIGHT);
    }

    @Test
    void nullBefore_throws() {
        assertThatThrownBy(() -> new IoTOrderingEntry(null, DeviceClass.LIGHT))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("before");
    }

    @Test
    void nullAfter_throws() {
        assertThatThrownBy(() -> new IoTOrderingEntry(DeviceClass.LOCK, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("after");
    }

    @Test
    void selfReference_throws() {
        assertThatThrownBy(() -> new IoTOrderingEntry(DeviceClass.LOCK, DeviceClass.LOCK))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Self-referencing");
    }
}
