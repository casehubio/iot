package io.casehub.iot.webapp.engine;

import io.casehub.iot.api.spi.DeviceProvider;
import io.casehub.iot.api.spi.DeviceRegistry;
import io.casehub.iot.webapp.worker.DeviceCommandWorkerFunction;
import io.casehub.iot.webapp.worker.HumanDecisionWorkerFunction;
import io.casehub.work.api.spi.WorkItemCreator;
import io.casehub.worker.api.Worker;
import jakarta.enterprise.inject.Instance;

import java.util.List;

public final class HvacAnomalyCaseDescriptor {

    private final Instance<DeviceProvider> providers;
    private final DeviceRegistry           deviceRegistry;
    private final WorkItemCreator          workItemCreator;

    public HvacAnomalyCaseDescriptor(
            final Instance<DeviceProvider> providers,
            final DeviceRegistry deviceRegistry,
            final WorkItemCreator workItemCreator) {
        this.providers       = providers;
        this.deviceRegistry  = deviceRegistry;
        this.workItemCreator = workItemCreator;
    }

    public List<Worker> workers() {
        return List.of(
                deviceCommandWorker(),
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

    private Worker humanReviewWorker() {
        return Worker.builder()
                     .name("human-review")
                     .capabilityName("human-review")
                     .function(new HumanDecisionWorkerFunction(workItemCreator))
                     .build();
    }
}
