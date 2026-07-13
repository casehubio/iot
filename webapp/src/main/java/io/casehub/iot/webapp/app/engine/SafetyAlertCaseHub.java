package io.casehub.iot.webapp.app.engine;

import io.casehub.api.engine.YamlCaseHub;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.cbr.CbrConfig;
import io.casehub.api.model.cbr.CbrConfig.CbrRetrievalTiming;
import io.casehub.iot.api.spi.DeviceProvider;
import io.casehub.iot.api.spi.DeviceRegistry;
import io.casehub.iot.webapp.cbr.IoTCbrFeatureExtractors;
import io.casehub.iot.webapp.engine.SafetyAlertCaseDescriptor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

@ApplicationScoped
public class SafetyAlertCaseHub extends YamlCaseHub {

    @Inject
    Instance<DeviceProvider> providers;

    @Inject
    DeviceRegistry registry;

    public SafetyAlertCaseHub() {
        super("iot/safety-alert.yaml");
    }

    @Override
    protected void augment(final CaseDefinition definition) {
        final var descriptor = new SafetyAlertCaseDescriptor(providers, registry);
        descriptor.workers().forEach(definition.getWorkers()::add);

        definition.setCbrConfig(CbrConfig.builder()
                .domain("iot")
                .caseType(definition.getName())
                .featureExtractor(IoTCbrFeatureExtractors::extractSafetyAlertFeatures)
                .weight("deviceClass", 2.0)
                .weight("alertType", 3.0)
                .weight("roomType", 1.5)
                .weight("hourOfDay", 1.0)
                .weight("dayType", 0.5)
                .weight("season", 0.5)
                .topK(5)
                .minSimilarity(0.3)
                .vectorWeight(0.0)
                .timing(CbrRetrievalTiming.PER_EVALUATION)
                .build());
    }
}
