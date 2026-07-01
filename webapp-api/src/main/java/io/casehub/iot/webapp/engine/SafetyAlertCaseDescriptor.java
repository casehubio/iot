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
 * Descriptor carrying business logic for safety-alert cases.
 *
 * <p>A plain POJO — no CDI annotations. Constructed by SafetyAlertCaseHub
 * with CDI-managed dependencies. Workers: device-command-dispatch (kill HVAC,
 * unlock doors), household-notification, human-acknowledgement.
 *
 * <p>Flow: immediately dispatch safety commands → notify household → create
 * WorkItem for acknowledgement. No waiting for human approval before automated
 * response — safety first.
 */
public final class SafetyAlertCaseDescriptor {

    private final Instance<DeviceProvider> providers;
    private final DeviceRegistry deviceRegistry;

    public SafetyAlertCaseDescriptor(
            final Instance<DeviceProvider> providers,
            final DeviceRegistry deviceRegistry) {
        this.providers = providers;
        this.deviceRegistry = deviceRegistry;
    }

    public List<Worker> workers() {
        return List.of(
                deviceCommandWorker(),
                householdNotificationWorker(),
                humanAcknowledgementWorker()
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

    private static Worker humanAcknowledgementWorker() {
        return Worker.builder()
                .name("human-acknowledgement")
                .capabilityName("human-acknowledgement")
                .function(new HumanDecisionWorkerFunction())
                .build();
    }
}
