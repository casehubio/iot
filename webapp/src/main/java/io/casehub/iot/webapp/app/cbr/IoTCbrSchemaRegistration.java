package io.casehub.iot.webapp.app.cbr;

import io.casehub.iot.webapp.cbr.IoTCbrFeatureSchemas;
import io.casehub.neocortex.memory.cbr.CbrRecordStore;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

@ApplicationScoped
public class IoTCbrSchemaRegistration {

    @Inject
    CbrRecordStore cbrStore;

    void onStartup(@Observes StartupEvent event) {
        cbrStore.registerSchema(IoTCbrFeatureSchemas.hvacAnomaly());
        cbrStore.registerSchema(IoTCbrFeatureSchemas.safetyAlert());
        cbrStore.registerSchema(IoTCbrFeatureSchemas.securityAlert());
        cbrStore.registerSchema(IoTCbrFeatureSchemas.genericResponse());
        cbrStore.registerSchema(IoTCbrFeatureSchemas.workItemOutcome());
        cbrStore.registerSchema(IoTCbrFeatureSchemas.situationDismissal());
    }
}
