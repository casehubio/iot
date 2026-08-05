package io.casehub.iot.webapp.app.resolution;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

import java.util.Map;

@Readiness
@ApplicationScoped
public class IoTAiResolutionReadinessCheck implements HealthCheck {

    private final IoTAiResolutionAgent agent;

    @Inject
    public IoTAiResolutionReadinessCheck(IoTAiResolutionAgent agent) {
        this.agent = agent;
    }

    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder builder = HealthCheckResponse.named("ai-resolution-agent")
                .status(agent.isReady());
        Map<String, Object> data = agent.healthData();
        data.forEach((key, value) -> {
            if (value instanceof Boolean b) {
                builder.withData(key, b);
            } else if (value instanceof Long l) {
                builder.withData(key, l);
            } else if (value instanceof Integer i) {
                builder.withData(key, (long) i);
            } else {
                builder.withData(key, String.valueOf(value));
            }
        });
        return builder.build();
    }
}
