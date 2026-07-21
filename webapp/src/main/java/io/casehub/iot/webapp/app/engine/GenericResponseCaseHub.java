package io.casehub.iot.webapp.app.engine;

import io.casehub.api.engine.YamlCaseHub;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.cbr.CbrConfig;
import io.casehub.api.model.cbr.CbrConfig.CbrRetrievalTiming;
import io.casehub.iot.webapp.app.triage.IoTTriageConfig;
import io.casehub.iot.webapp.cbr.IoTCbrFeatureExtractors;
import io.casehub.iot.webapp.engine.GenericResponseCaseDescriptor;
import io.casehub.work.api.spi.WorkItemCreator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class GenericResponseCaseHub extends YamlCaseHub {
    @Inject
    WorkItemCreator workItemCreator;
    @Inject
    IoTTriageConfig triageConfig;


    public GenericResponseCaseHub() {
        super("iot/generic-response.yaml");
    }

    @Override
    protected void augment(final CaseDefinition definition) {
        final var descriptor = new GenericResponseCaseDescriptor(workItemCreator);
        descriptor.workers().forEach(definition.getWorkers()::add);

        definition.setCbrConfig(CbrConfig.builder()
                                         .domain("iot")
                                         .caseType(definition.getName())
                                         .featureExtractor(IoTCbrFeatureExtractors::extractGenericResponseFeatures)
                                         .weight("deviceClass", 2.0)
                                         .weight("roomType", 1.5)
                                         .weight("hourOfDay", 1.0)
                                         .weight("dayType", 0.5)
                                         .weight("season", 0.5)
                                         .topK(5)
                                         .minSimilarity(0.3)
                                         .vectorWeight(0.0)
                                         .timing(CbrRetrievalTiming.PER_EVALUATION)
                                         .build());

        definition.setLabelRules(
                io.casehub.iot.webapp.cbr.IoTTriageLabelRules.cbrTriageRules(
                        triageConfig.aiMinSimilarity(), triageConfig.aiMinConsistency()));
    }
}
