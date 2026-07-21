package io.casehub.iot.webapp.app.engine;

import io.casehub.api.engine.YamlCaseHub;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.cbr.CbrConfig;
import io.casehub.api.model.cbr.CbrConfig.CbrRetrievalTiming;
import io.casehub.iot.api.spi.DeviceProvider;
import io.casehub.iot.api.spi.DeviceRegistry;
import io.casehub.iot.webapp.app.triage.IoTTriageConfig;
import io.casehub.iot.webapp.cbr.IoTCbrFeatureExtractors;
import io.casehub.iot.webapp.engine.HvacAnomalyCaseDescriptor;
import io.casehub.work.api.spi.WorkItemCreator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

@ApplicationScoped
public class HvacAnomalyCaseHub extends YamlCaseHub {

    @Inject
    Instance<DeviceProvider> providers;

    @Inject
    DeviceRegistry  registry;
    @Inject
    WorkItemCreator workItemCreator;
    @Inject
    IoTTriageConfig triageConfig;


    public HvacAnomalyCaseHub() {
        super("iot/hvac-anomaly.yaml");
    }

    @Override
    protected void augment(final CaseDefinition definition) {
        final var descriptor = new HvacAnomalyCaseDescriptor(providers, registry, workItemCreator);
        descriptor.workers().forEach(definition.getWorkers()::add);

        definition.setCbrConfig(CbrConfig.builder()
                                         .domain("iot")
                                         .caseType(definition.getName())
                                         .featureExtractor(IoTCbrFeatureExtractors::extractHvacAnomalyFeatures)
                                         .weight("deviceClass", 2.0)
                                         .weight("roomType", 1.5)
                                         .weight("temperatureDelta", 1.5)
                                         .weight("hourOfDay", 1.0)
                                         .weight("outdoorTemperatureRange", 0.8)
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
