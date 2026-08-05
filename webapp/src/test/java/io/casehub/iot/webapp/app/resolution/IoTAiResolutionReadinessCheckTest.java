package io.casehub.iot.webapp.app.resolution;

import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IoTAiResolutionReadinessCheckTest {

    @Test
    void up_whenAgentIsReady() {
        IoTAiResolutionAgent agent = mock(IoTAiResolutionAgent.class);
        when(agent.isReady()).thenReturn(true);
        when(agent.healthData()).thenReturn(Map.of(
                "enabled", true,
                "aiResolutionViewResolved", true,
                "operatorAssistedViewResolved", true,
                "semaphorePermits", 3));

        IoTAiResolutionReadinessCheck check = new IoTAiResolutionReadinessCheck(agent);
        HealthCheckResponse response = check.call();

        assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.Status.UP);
        assertThat(response.getName()).isEqualTo("ai-resolution-agent");
        assertThat(response.getData().get().get("semaphorePermits")).isEqualTo(3L);
    }

    @Test
    void down_whenAgentNotReady() {
        IoTAiResolutionAgent agent = mock(IoTAiResolutionAgent.class);
        when(agent.isReady()).thenReturn(false);
        when(agent.healthData()).thenReturn(Map.of(
                "enabled", false,
                "aiResolutionViewResolved", false,
                "operatorAssistedViewResolved", false,
                "semaphorePermits", 0));

        IoTAiResolutionReadinessCheck check = new IoTAiResolutionReadinessCheck(agent);
        HealthCheckResponse response = check.call();

        assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.Status.DOWN);
    }
}
