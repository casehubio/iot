package io.casehub.iot.webapp.app.cbr;

import io.casehub.iot.api.spi.DeviceRegistry;
import io.casehub.iot.webapp.cbr.DismissalRecorder;
import io.casehub.iot.webapp.cbr.IoTSuppressionTriggerPolicy;
import io.casehub.iot.webapp.cbr.SuppressionConfig;
import io.casehub.iot.webapp.cbr.SuppressionEvaluator;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.ras.api.RasTriggerPolicy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

@ApplicationScoped
public class IoTSuppressionProducer {

    @Inject
    CbrCaseMemoryStore cbrStore;

    @Inject
    DeviceRegistry deviceRegistry;

    @Inject
    SuppressionConfigMapping configMapping;

    @Produces
    @jakarta.inject.Singleton
    SuppressionConfig suppressionConfig() {
        return new SuppressionConfig(
                configMapping.fullThreshold(),
                configMapping.demotionThreshold(),
                configMapping.minCases(),
                configMapping.topK(),
                configMapping.minSimilarity());
    }

    @Produces
    @ApplicationScoped
    SuppressionEvaluator suppressionEvaluator(SuppressionConfig config) {
        return new SuppressionEvaluator(cbrStore, config);
    }

    @Produces
    @ApplicationScoped
    DismissalRecorder dismissalRecorder() {
        return new DismissalRecorder(cbrStore, deviceRegistry);
    }

    @Produces
    @ApplicationScoped
    RasTriggerPolicy ioTSuppressionTriggerPolicy(SuppressionEvaluator evaluator) {
        return new IoTSuppressionTriggerPolicy(evaluator, deviceRegistry);
    }
}
