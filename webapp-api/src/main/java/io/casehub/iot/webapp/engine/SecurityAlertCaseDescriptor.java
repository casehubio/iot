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
 * Descriptor carrying business logic for security-alert cases.
 *
 * <p>A plain POJO — no CDI annotations. Constructed by SecurityAlertCaseHub
 * with CDI-managed dependencies. Workers: device-command-dispatch (lock doors,
 * activate cameras), camera-activation, household-notification, human-decision.
 *
 * <p>Flow: lock doors + activate cameras → notify household → create WorkItem
 * for human decision (false alarm / escalate / call authorities). Automated lock
 * is immediate, but escalation requires human WorkItem approval.
 */
public final class SecurityAlertCaseDescriptor {

    private final Instance<DeviceProvider> providers;
    private final DeviceRegistry deviceRegistry;

    public SecurityAlertCaseDescriptor(
            final Instance<DeviceProvider> providers,
            final DeviceRegistry deviceRegistry) {
        this.providers = providers;
        this.deviceRegistry = deviceRegistry;
    }

    public List<Worker> workers() {
        return List.of(
                deviceCommandWorker(),
                cameraActivationWorker(),
                householdNotificationWorker(),
                humanDecisionWorker()
        );
    }

    private Worker deviceCommandWorker() {
        return Worker.builder()
                .name("device-command-dispatch")
                .capabilityName("device-command-dispatch")
                .function(new DeviceCommandWorkerFunction(providers, deviceRegistry))
                .build();
    }

    private Worker cameraActivationWorker() {
        return Worker.builder()
                .name("camera-activation")
                .capabilityName("camera-activation")
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

    private static Worker humanDecisionWorker() {
        return Worker.builder()
                .name("human-decision")
                .capabilityName("human-decision")
                .function(new HumanDecisionWorkerFunction())
                .build();
    }
}
