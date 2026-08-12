package io.casehub.iot.webapp.engine;

import io.casehub.iot.api.spi.DeviceProvider;
import io.casehub.iot.api.spi.DeviceRegistry;
import io.casehub.work.api.spi.WorkItemCreator;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SecurityAlertCaseDescriptorTest {

    @SuppressWarnings("unchecked")
    @Test
    void workersDoNotIncludeHouseholdNotification() {
        var descriptor = new SecurityAlertCaseDescriptor(
                mock(Instance.class), mock(DeviceRegistry.class), mock(WorkItemCreator.class));
        var names = descriptor.workers().stream().map(w -> w.name()).toList();
        assertThat(names).containsExactly("device-command-dispatch", "camera-activation", "human-decision");
        assertThat(names).doesNotContain("household-notification");
    }
}
