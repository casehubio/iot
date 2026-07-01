package io.casehub.iot.webapp.engine;

import io.casehub.iot.api.spi.DeviceProvider;
import io.casehub.iot.api.spi.DeviceRegistry;
import io.casehub.iot.webapp.worker.DeviceCommandWorkerFunction;
import io.casehub.iot.webapp.worker.HouseholdNotificationWorkerFunction;
import io.casehub.iot.webapp.worker.HumanDecisionWorkerFunction;
import io.casehub.worker.api.Worker;
import jakarta.enterprise.inject.Instance;

import java.util.List;

/**
 * Descriptor carrying business logic for hvac-anomaly cases.
 *
 * <p>A plain POJO — no CDI annotations. Constructed by HvacAnomalyCaseHub
 * with CDI-managed dependencies. Workers: device-command-dispatch (setpoint
 * correction), household-notification, human-review.
 *
 * <p>Flow: attempt setpoint correction → if command fails or temperature
 * doesn't respond → notify household → create WorkItem for manual triage.
 */
public final class HvacAnomalyCaseDescriptor {

    private final Instance<DeviceProvider> providers;
    private final DeviceRegistry deviceRegistry;

    public HvacAnomalyCaseDescriptor(
            final Instance<DeviceProvider> providers,
            final DeviceRegistry deviceRegistry) {
        this.providers = providers;
        this.deviceRegistry = deviceRegistry;
    }

    public List<Worker> workers() {
        return List.of(
                deviceCommandWorker(),
                householdNotificationWorker(),
                humanReviewWorker()
        );
    }

    private Worker deviceCommandWorker() {
        return Worker.builder()
                .name("device-command-dispatch")
                .capabilityName("device-command-dispatch")
                .function(new DeviceCommandWorkerFunction(providers, deviceRegistry))
                .build();
    }

    private static Worker householdNotificationWorker() {
        return Worker.builder()
                .name("household-notification")
                .capabilityName("household-notification")
                .function(new HouseholdNotificationWorkerFunction())
                .build();
    }

    private static Worker humanReviewWorker() {
        return Worker.builder()
                .name("human-review")
                .capabilityName("human-review")
                .function(new HumanDecisionWorkerFunction())
                .build();
    }
}
