package io.casehub.iot.webapp.engine;

import io.casehub.iot.webapp.worker.HumanDecisionWorkerFunction;
import io.casehub.worker.api.Worker;

import java.util.List;

/**
 * Descriptor carrying business logic for generic-response cases.
 *
 * <p>A plain POJO — no CDI annotations. No CDI dependencies required —
 * this case type only creates human WorkItems. Workers: human-triage.
 *
 * <p>Flow: create WorkItem with situation context (detections, confidence,
 * device states). Human decides what to do. No automated device commands.
 * This is the target case for runtime-defined situations where the user
 * hasn't specified automated responses.
 */
public final class GenericResponseCaseDescriptor {

    public List<Worker> workers() {
        return List.of(humanTriageWorker());
    }

    private static Worker humanTriageWorker() {
        return Worker.builder()
                .name("human-triage")
                .capabilityName("human-triage")
                .function(new HumanDecisionWorkerFunction())
                .build();
    }
}
