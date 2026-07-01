package io.casehub.iot.webapp.app.engine;

import io.casehub.api.engine.YamlCaseHub;
import io.casehub.api.engine.definition.CaseDefinition;
import io.casehub.iot.api.spi.DeviceProvider;
import io.casehub.iot.api.spi.DeviceRegistry;
import io.casehub.iot.webapp.engine.HvacAnomalyCaseDescriptor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * CaseHub for hvac-anomaly cases triggered by HVAC failure situations.
 *
 * <p>Loads YAML structure from classpath and augments with worker lambdas
 * from HvacAnomalyCaseDescriptor. Flow: attempt setpoint correction → if
 * command fails or temperature doesn't respond → notify household → human
 * review for manual triage.
 *
 * <p>Referenced by RAS CaseTriggerConfig: {@code ("io.casehub.iot", "hvac-anomaly", "1.0")}
 */
@ApplicationScoped
public class HvacAnomalyCaseHub extends YamlCaseHub {

    @Inject
    Instance<DeviceProvider> providers;

    @Inject
    DeviceRegistry registry;

    public HvacAnomalyCaseHub() {
        super("iot/hvac-anomaly.yaml");
    }

    @Override
    protected void augment(final CaseDefinition definition) {
        final var descriptor = new HvacAnomalyCaseDescriptor(providers, registry);
        descriptor.workers().forEach(definition::registerWorker);
    }
}
