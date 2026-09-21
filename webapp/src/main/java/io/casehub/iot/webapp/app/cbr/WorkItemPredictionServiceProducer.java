package io.casehub.iot.webapp.app.cbr;

import io.casehub.iot.webapp.cbr.WorkItemPredictionService;
import io.casehub.neocortex.memory.cbr.CbrRecordStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

@ApplicationScoped
public class WorkItemPredictionServiceProducer {

    @Inject
    CbrRecordStore cbrStore;

    @Inject
    WorkItemCbrConfig config;

    @Produces
    @ApplicationScoped
    public WorkItemPredictionService predictionService() {
        return new WorkItemPredictionService(cbrStore, config.topK(), config.minSimilarity());
    }
}
