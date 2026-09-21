package io.casehub.iot.webapp.app.cbr;

import io.casehub.iot.webapp.cbr.IoTCbrRetrievalService;
import io.casehub.neocortex.memory.cbr.CbrRecordStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

@ApplicationScoped
public class IoTCbrRetrievalServiceProducer {

    @Inject
    CbrRecordStore cbrStore;

    @Produces
    @ApplicationScoped
    public IoTCbrRetrievalService retrievalService() {
        return new IoTCbrRetrievalService(cbrStore);
    }
}
