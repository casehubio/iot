package io.casehub.iot.webapp.app.engine;

import io.casehub.api.engine.YamlCaseHub;
import io.casehub.api.engine.definition.CaseDefinition;
import io.casehub.iot.webapp.engine.GenericResponseCaseDescriptor;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * CaseHub for generic-response cases triggered by runtime-defined situations.
 *
 * <p>Loads YAML structure from classpath and augments with worker lambdas
 * from GenericResponseCaseDescriptor. Flow: create WorkItem with situation
 * context (detections, confidence, device states). Human decides what to do.
 * No automated device commands — pure triage workflow.
 *
 * <p>Referenced by RAS CaseTriggerConfig: {@code ("io.casehub.iot", "generic-response", "1.0")}
 */
@ApplicationScoped
public class GenericResponseCaseHub extends YamlCaseHub {

    public GenericResponseCaseHub() {
        super("iot/generic-response.yaml");
    }

    @Override
    protected void augment(final CaseDefinition definition) {
        final var descriptor = new GenericResponseCaseDescriptor();
        descriptor.workers().forEach(definition::registerWorker);
    }
}
